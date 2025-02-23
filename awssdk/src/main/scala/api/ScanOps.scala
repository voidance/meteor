package meteor
package api

import cats.effect.Async
import cats.implicits._
import fs2.{Pipe, RaiseThrowable, Stream}
import meteor.codec.Decoder
import meteor.errors.InvalidExpression
import meteor.implicits._
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model._

import scala.jdk.CollectionConverters._

private[meteor] trait ScanOps {

  private[meteor] def scanOp[F[_]: Async: RaiseThrowable, T: Decoder](
    tableName: String,
    filter: Expression,
    consistentRead: Boolean,
    parallelism: Int
  )(jClient: DynamoDbAsyncClient): Stream[F, T] = {
    def requestBuilder(
      filter: Expression,
      startKey: Option[java.util.Map[String, AttributeValue]]
    ) = {
      def builder(filter: Expression) = {
        ScanRequest.builder()
          .tableName(tableName)
          .consistentRead(consistentRead)
          .filterExpression(filter.expression)
          .expressionAttributeNames(filter.attributeNames.asJava)
          .expressionAttributeValues(filter.attributeValues.asJava)
          .totalSegments(parallelism)
      }

      startKey.fold(builder(filter))(builder(filter).exclusiveStartKey)
    }

    def initRequests(filter: Expression) =
      Stream.emits[F, SegmentPassThrough[ScanRequest]](
        List.fill(parallelism)(
          requestBuilder(filter, None)
        ).zipWithIndex.map {
          case (builder, index) =>
            SegmentPassThrough(builder.segment(index).build(), index)
        }
      )

    def sendPipe(filter: Expression): Pipe[
      F,
      SegmentPassThrough[ScanRequest],
      SegmentPassThrough[ScanResponse]
    ] =
      _.map(req => loop(filter, req)).parJoin(parallelism)

    def loop(
      filter: Expression,
      req: SegmentPassThrough[ScanRequest]
    ): Stream[F, SegmentPassThrough[ScanResponse]] =
      Stream.eval(liftFuture(jClient.scan(req.u))).flatMap { resp =>
        Stream.emit(SegmentPassThrough(resp, req.segment)) ++ {
          if (resp.hasLastEvaluatedKey && !resp.lastEvaluatedKey().isEmpty) {
            val nextReq =
              SegmentPassThrough(
                requestBuilder(filter, Some(resp.lastEvaluatedKey())).segment(
                  req.segment
                ).build(),
                req.segment
              )
            loop(filter, nextReq)
          } else {
            Stream.empty
          }
        }
      }

    for {
      cond <- Stream.eval(
        Async[F].fromOption(
          filter.nonEmpty.guard[Option].as(filter),
          InvalidExpression
        )
      )
      resp <- sendPipe(cond)(initRequests(cond))
      attrs <- fs2.Stream.emits(resp.u.items().asScala.toList)
      t <- fs2.Stream.fromEither(attrs.asAttributeValue.as[T])
    } yield t
  }

  private[meteor] def scanOp[F[_]: Async: RaiseThrowable, T: Decoder](
    tableName: String,
    consistentRead: Boolean,
    parallelism: Int,
    initialKey: Option[java.util.Map[String, AttributeValue]] = None
  )(jClient: DynamoDbAsyncClient)
    : fs2.Stream[F, (Option[java.util.Map[String, AttributeValue]], T)] = {

    def requestBuilder(
      startKey: Option[java.util.Map[String, AttributeValue]]
    ) = {
      val builder =
        ScanRequest.builder()
          .tableName(tableName)
          .consistentRead(consistentRead)
          .totalSegments(parallelism)

      startKey.fold(builder)(builder.exclusiveStartKey)
    }

    lazy val initRequests =
      Stream.range(0, parallelism).map { index =>
        val builder = requestBuilder(initialKey)
        SegmentPassThrough(builder.segment(index).build(), index)
      }

    def loop(
      req: SegmentPassThrough[ScanRequest]
    ): Stream[F, SegmentPassThrough[ScanResponse]] =
      Stream.eval(liftFuture(jClient.scan(req.u))).flatMap { resp =>
        Stream.emit(SegmentPassThrough(resp, req.segment)).covary[F] ++ {
          if (resp.hasLastEvaluatedKey && !resp.lastEvaluatedKey().isEmpty) {
            val nextReq = SegmentPassThrough(
              requestBuilder(Some(resp.lastEvaluatedKey())).segment(
                req.segment
              ).build(),
              req.segment
            )
            loop(nextReq)
          } else {
            Stream.empty
          }
        }
      }

    val sendPipe: Pipe[
      F,
      SegmentPassThrough[ScanRequest],
      SegmentPassThrough[ScanResponse]
    ] =
      _.map(loop).parJoin(parallelism)

    for {
      resp <- sendPipe(initRequests)
      attrs <- fs2.Stream.emits(resp.u.items().asScala.toList)
      t <- fs2.Stream.fromEither(attrs.asAttributeValue.as[T])
    } yield (Option(resp.u.lastEvaluatedKey()), t)
  }

  private case class SegmentPassThrough[U](
    u: U,
    segment: Int
  )
}
