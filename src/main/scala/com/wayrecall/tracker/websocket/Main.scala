package com.wayrecall.tracker.websocket

import zio.*
import zio.http.*
import com.wayrecall.tracker.websocket.api.*
import com.wayrecall.tracker.websocket.config.*
import com.wayrecall.tracker.websocket.kafka.*
import com.wayrecall.tracker.websocket.service.*

// ============================================================
// WEBSOCKET SERVICE — ТОЧКА ВХОДА
// ============================================================
//
// Ответственности:
// - Real-time трансляция GPS позиций через WebSocket
// - Доставка событий геозон и нарушений скорости
// - Smart Consumer: читает gps-events из Kafka, фильтрует
//   in-memory по activeSubscriptions, отправляет в WS каналы
//
// Архитектура:
//
// ┌──────────────────────────────────────────────────────────┐
// │                   WebSocket Service                       │
// ├──────────────────────────────────────────────────────────┤
// │                                                           │
// │  ┌──────────────┐        ┌──────────────────────────┐   │
// │  │ Kafka        │        │ HTTP/WS Server :8090     │   │
// │  │ GPS Consumer │        │  /ws     (WebSocket)     │   │
// │  │ Events Cons. │        │  /health (REST)          │   │
// │  └──────┬───────┘        └───────────┬──────────────┘   │
// │         │                            │                    │
// │         │   ┌────────────────────┐   │                    │
// │         └──►│  MessageRouter     │◄──┘                    │
// │             │  + Throttler       │                         │
// │             └─────────┬──────────┘                         │
// │                       │                                    │
// │             ┌─────────▼──────────┐                         │
// │             │ ConnectionRegistry │                         │
// │             │ (in-memory Refs)   │                         │
// │             └────────────────────┘                         │
// └──────────────────────────────────────────────────────────┘
//
object Main extends ZIOAppDefault:

  /**
   * Слой конфигурации: загрузка из application.conf
   * и разбиение на подконфигурации для каждого компонента.
   */
  private val configLayers: ZLayer[Any, Throwable, AppConfig & KafkaConfig & HttpConfig & ThrottleConfig] =
    AppConfig.live.flatMap { env =>
      val config = env.get[AppConfig]
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.http) ++
      ZLayer.succeed(config.throttle)
    }

  /**
   * Точка входа приложения
   */
  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    (for
      _ <- ZIO.succeed(java.lang.System.out.println(">>> WebSocket Service Main starting..."))
      result <- program
        .provide(
          // Конфигурация
          configLayers,

          // Сервисы (in-memory state)
          ConnectionRegistry.live,
          PositionThrottler.live,
          MessageRouter.live,

          // Kafka consumers
          GpsEventConsumer.live,
          EventConsumer.live,

          // HTTP/WS Server (порт из конфига)
          ZLayer.fromZIO(
            ZIO.serviceWith[AppConfig](config =>
              Server.Config.default.port(config.http.port)
            )
          ) >>> Server.live
        )
    yield result).tapError(e =>
      ZIO.succeed(java.lang.System.err.println(s">>> WS FATAL ERROR: $e"))
    ).catchAll(e =>
      ZIO.succeed(java.lang.System.err.println(s">>> WS CAUGHT: $e")) *> ZIO.fail(e)
    ).exitCode

  /**
   * Основная программа.
   *
   * Запускает параллельно:
   * 1. HTTP/WS сервер (health + WebSocket endpoints)
   * 2. Kafka GPS Consumer (gps-events → MessageRouter → WS клиенты)
   * 3. Kafka Events Consumer (geozone-events, rule-violations → WS клиенты)
   *
   * race гарантирует: если любой компонент упадёт, все остановятся.
   */
  private val program: ZIO[
    AppConfig & ConnectionRegistry & MessageRouter &
    GpsEventConsumer & EventConsumer & Server,
    Throwable,
    Unit
  ] =
    for
      config <- ZIO.service[AppConfig]

      // Баннер
      _ <- printBanner(config)

      // Собираем маршруты (health + WebSocket)
      allRoutes = HealthRoutes.routes ++ WebSocketHandler.routes

      // Запускаем все компоненты параллельно
      _ <- ZIO.logInfo("Запуск WebSocket Service...")

      _ <- (
        // HTTP/WS сервер
        ZIO.logInfo(s"Запуск HTTP/WS сервера на порту ${config.http.port}") *>
        Server.serve(allRoutes)
      ).race(
        // GPS events consumer — позиции в реальном времени
        ZIO.logInfo(s"Запуск GPS Consumer: topic=${config.kafka.gpsEventsTopic}") *>
        ZIO.serviceWithZIO[GpsEventConsumer](_.run)
      ).race(
        // Business events consumer — геозоны, скорость
        ZIO.logInfo(s"Запуск Events Consumer: topics=[${config.kafka.geozoneEventsTopic}, ${config.kafka.ruleViolationsTopic}]") *>
        ZIO.serviceWithZIO[EventConsumer](_.run)
      )
    yield ()

  /**
   * Баннер с информацией о сервисе
   */
  private def printBanner(config: AppConfig): UIO[Unit] =
    val banner = s"""
      |╔══════════════════════════════════════════════════════════════╗
      |║                   WEBSOCKET SERVICE                          ║
      |║     Real-time трансляция GPS и событий через WebSocket       ║
      |╠══════════════════════════════════════════════════════════════╣
      |║  HTTP/WS Port: ${config.http.port.toString.padTo(44, ' ')}║
      |║  Kafka:        ${config.kafka.bootstrapServers.take(44).padTo(44, ' ')}║
      |║  GPS Topic:    ${config.kafka.gpsEventsTopic.padTo(44, ' ')}║
      |║  Events:       ${s"${config.kafka.geozoneEventsTopic}, ${config.kafka.ruleViolationsTopic}".take(44).padTo(44, ' ')}║
      |║  Throttle:     ${s"${config.throttle.positionIntervalMs}ms per vehicle".padTo(44, ' ')}║
      |╚══════════════════════════════════════════════════════════════╝
    """.stripMargin

    ZIO.logInfo(banner)
