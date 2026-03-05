package com.wayrecall.tracker.websocket.kafka

import zio.*
import zio.kafka.consumer.*
import zio.kafka.serde.*
import zio.kafka.consumer.diagnostics.Diagnostics
import zio.json.*
import com.wayrecall.tracker.websocket.domain.GpsPoint
import com.wayrecall.tracker.websocket.config.KafkaConfig
import com.wayrecall.tracker.websocket.service.MessageRouter

// ============================================================
// KAFKA CONSUMER — GPS ПОЗИЦИИ
// ============================================================
// Читает топик gps-events (все GPS точки от Connection Manager).
// Consumer group: ws-positions — отдельная от других сервисов.
// auto.offset.reset = latest — нас интересует только real-time.
// Каждая точка передаётся в MessageRouter для роутинга клиентам.
// ============================================================

trait GpsEventConsumer:
  def run: Task[Unit]

object GpsEventConsumer:
  val live: ZLayer[MessageRouter & KafkaConfig, Nothing, GpsEventConsumer] =
    ZLayer.fromFunction(GpsEventConsumerLive(_, _))

private final class GpsEventConsumerLive(
    router: MessageRouter,
    kafkaConfig: KafkaConfig
) extends GpsEventConsumer:

  override def run: Task[Unit] =
    val settings = ConsumerSettings(List(kafkaConfig.bootstrapServers))
      .withGroupId(kafkaConfig.positionsGroupId)
      .withProperty("auto.offset.reset", "latest")
      .withProperty("max.poll.records", "500")

    ZIO.logInfo(
      s"Запуск GPS Consumer: topic=${kafkaConfig.gpsEventsTopic}, group=${kafkaConfig.positionsGroupId}"
    ) *>
    Consumer
      .plainStream(Subscription.topics(kafkaConfig.gpsEventsTopic), Serde.string, Serde.string)
      .mapZIO { record =>
        record.value.fromJson[GpsPoint] match
          case Right(point) =>
            router.routeGpsEvent(point)
          case Left(err) =>
            ZIO.logWarning(s"Ошибка парсинга GPS точки: $err")
      }
      .runDrain
      .provide(
        ZLayer.succeed(settings),
        ZLayer.succeed(Diagnostics.NoOp),
        Consumer.live
      )
