package com.wayrecall.tracker.websocket.kafka

import zio.*
import zio.kafka.consumer.*
import zio.kafka.serde.*
import zio.kafka.consumer.diagnostics.Diagnostics
import zio.json.*
import com.wayrecall.tracker.websocket.domain.*
import com.wayrecall.tracker.websocket.config.KafkaConfig
import com.wayrecall.tracker.websocket.service.MessageRouter

// ============================================================
// KAFKA CONSUMER — БИЗНЕС-СОБЫТИЯ
// ============================================================
// Читает топики geozone-events и rule-violations от Rule Checker.
// Consumer group: ws-events — отдельная от GPS consumer'а.
// События доставляются клиентам БЕЗ throttle (важные алерты).
// ============================================================

trait EventConsumer:
  def run: Task[Unit]

object EventConsumer:
  val live: ZLayer[MessageRouter & KafkaConfig, Nothing, EventConsumer] =
    ZLayer.fromFunction(EventConsumerLive(_, _))

private final class EventConsumerLive(
    router: MessageRouter,
    kafkaConfig: KafkaConfig
) extends EventConsumer:

  override def run: Task[Unit] =
    val settings = ConsumerSettings(List(kafkaConfig.bootstrapServers))
      .withGroupId(kafkaConfig.eventsGroupId)
      .withProperty("auto.offset.reset", "latest")
      .withProperty("max.poll.records", "100")

    ZIO.logInfo(
      s"Запуск Events Consumer: topics=[${kafkaConfig.geozoneEventsTopic}, ${kafkaConfig.ruleViolationsTopic}], group=${kafkaConfig.eventsGroupId}"
    ) *>
    Consumer
      .plainStream(
        Subscription.topics(kafkaConfig.geozoneEventsTopic, kafkaConfig.ruleViolationsTopic),
        Serde.string,
        Serde.string
      )
      .mapZIO { record =>
        // Роутинг по топику — каждый топик имеет свою доменную модель
        val topic = record.record.topic()
        if topic == kafkaConfig.geozoneEventsTopic then
          record.value.fromJson[GeozoneEvent] match
            case Right(event) => router.routeGeozoneEvent(event)
            case Left(err)    => ZIO.logWarning(s"Ошибка парсинга geozone event: $err")
        else if topic == kafkaConfig.ruleViolationsTopic then
          record.value.fromJson[SpeedViolationEvent] match
            case Right(event) => router.routeSpeedViolation(event)
            case Left(err)    => ZIO.logWarning(s"Ошибка парсинга speed violation: $err")
        else
          ZIO.logWarning(s"Неизвестный топик: $topic")
      }
      .runDrain
      .provide(
        ZLayer.succeed(settings),
        ZLayer.succeed(Diagnostics.NoOp),
        Consumer.live
      )
