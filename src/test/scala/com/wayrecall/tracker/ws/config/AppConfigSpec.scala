package com.wayrecall.tracker.websocket.config

import com.wayrecall.tracker.websocket.config.*
import zio.*
import zio.test.*

// ============================================================
// Тесты AppConfig — конфигурация WebSocket сервиса
// ============================================================

object AppConfigSpec extends ZIOSpecDefault:

  def spec = suite("AppConfig")(
    test("KafkaConfig — все поля заполнены") {
      val config = KafkaConfig(
        bootstrapServers = "localhost:9092",
        gpsEventsTopic = "gps-events",
        geozoneEventsTopic = "geozone-events",
        ruleViolationsTopic = "rule-violations",
        positionsGroupId = "ws-positions",
        eventsGroupId = "ws-events"
      )
      assertTrue(
        config.bootstrapServers == "localhost:9092",
        config.gpsEventsTopic == "gps-events",
        config.geozoneEventsTopic == "geozone-events",
        config.ruleViolationsTopic == "rule-violations",
        config.positionsGroupId == "ws-positions",
        config.eventsGroupId == "ws-events"
      )
    },

    test("HttpConfig — порт по умолчанию") {
      val config = HttpConfig(port = 8090)
      assertTrue(config.port == 8090)
    },

    test("ThrottleConfig — интервал в миллисекундах") {
      val config = ThrottleConfig(positionIntervalMs = 1000)
      assertTrue(config.positionIntervalMs == 1000)
    },

    test("AppConfig — сборка из компонентов") {
      val config = AppConfig(
        kafka = KafkaConfig("localhost:9092", "gps", "geo", "rules", "pg", "eg"),
        http = HttpConfig(8090),
        throttle = ThrottleConfig(500)
      )
      assertTrue(
        config.kafka.bootstrapServers == "localhost:9092",
        config.http.port == 8090,
        config.throttle.positionIntervalMs == 500
      )
    },

    test("ThrottleConfig — нулевой интервал (без throttling)") {
      val config = ThrottleConfig(positionIntervalMs = 0)
      assertTrue(config.positionIntervalMs == 0)
    }
  ) @@ TestAspect.timeout(60.seconds)
