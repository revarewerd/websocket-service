package com.wayrecall.tracker.websocket.config

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*

// ============================================================
// КОНФИГУРАЦИЯ WEBSOCKET SERVICE
// ============================================================

/** Конфигурация Kafka — топики и consumer groups */
final case class KafkaConfig(
    bootstrapServers: String,
    gpsEventsTopic: String,         // gps-events — позиции от CM
    geozoneEventsTopic: String,     // geozone-events — события от Rule Checker
    ruleViolationsTopic: String,    // rule-violations — нарушения от Rule Checker
    positionsGroupId: String,       // consumer group для позиций
    eventsGroupId: String           // consumer group для бизнес-событий
)

/** Конфигурация HTTP сервера (health + WebSocket на одном порту) */
final case class HttpConfig(
    port: Int
)

/** Конфигурация throttling позиций */
final case class ThrottleConfig(
    positionIntervalMs: Long        // мин. интервал между обновлениями для одного ТС (мс)
)

/** Корневая конфигурация приложения */
final case class AppConfig(
    kafka: KafkaConfig,
    http: HttpConfig,
    throttle: ThrottleConfig
)

object AppConfig:

  /** ZIO Layer для загрузки конфигурации из application.conf */
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config[AppConfig](
        deriveConfig[AppConfig].mapKey(toKebabCase)
      )
    )

  /** Производные слои для подконфигураций */
  val allLayers: ZLayer[Any, Config.Error, AppConfig & KafkaConfig & HttpConfig & ThrottleConfig] =
    live.flatMap { env =>
      val config = env.get[AppConfig]
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.http) ++
      ZLayer.succeed(config.throttle)
    }
