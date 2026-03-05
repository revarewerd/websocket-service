package com.wayrecall.tracker.websocket.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.Instant

// ============================================================
// ТЕСТЫ ДОМЕННЫХ МОДЕЛЕЙ — JSON сериализация, opaque types
// ============================================================

object DomainSpec extends ZIOSpecDefault:

  def spec = suite("Domain")(
    opaqueTypesSuite,
    clientMessagesSuite,
    serverMessagesSuite,
    kafkaModelsSuite
  )

  // === Opaque Types ===

  private val opaqueTypesSuite = suite("Opaque Types")(
    test("VehicleId — создание и извлечение") {
      val id = VehicleId(123L)
      assertTrue(id.value == 123L)
    },
    test("DeviceId — создание и извлечение") {
      val id = DeviceId(456L)
      assertTrue(id.value == 456L)
    },
    test("OrganizationId — создание и извлечение") {
      val id = OrganizationId(100L)
      assertTrue(id.value == 100L)
    },
    test("GeozoneId — создание и извлечение") {
      val id = GeozoneId(789L)
      assertTrue(id.value == 789L)
    },
    test("Imei — создание и извлечение") {
      val imei = Imei("352094080055555")
      assertTrue(imei.value == "352094080055555")
    },
    test("SpeedRuleId — создание и извлечение") {
      val id = SpeedRuleId(42L)
      assertTrue(id.value == 42L)
    },
    test("VehicleId — JSON roundtrip") {
      val id = VehicleId(123L)
      val json = id.toJson
      val decoded = json.fromJson[VehicleId]
      assertTrue(json == "123") && assertTrue(decoded == Right(VehicleId(123L)))
    },
    test("OrganizationId — JSON roundtrip") {
      val id = OrganizationId(100L)
      val json = id.toJson
      val decoded = json.fromJson[OrganizationId]
      assertTrue(json == "100") && assertTrue(decoded == Right(OrganizationId(100L)))
    }
  )

  // === ClientMessage — десериализация ===

  private val clientMessagesSuite = suite("ClientMessage")(
    test("decode Subscribe") {
      val json = """{"type":"subscribe","vehicleIds":[1,2,3]}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(
        result == Right(ClientMessage.Subscribe(List(1, 2, 3)))
      )
    },
    test("decode SubscribeOrg") {
      val json = """{"type":"subscribeOrg"}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result == Right(ClientMessage.SubscribeOrg()))
    },
    test("decode Unsubscribe") {
      val json = """{"type":"unsubscribe","vehicleIds":[2]}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(
        result == Right(ClientMessage.Unsubscribe(List(2)))
      )
    },
    test("decode UnsubscribeAll") {
      val json = """{"type":"unsubscribeAll"}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result == Right(ClientMessage.UnsubscribeAll()))
    },
    test("decode Ping") {
      val json = """{"type":"ping"}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result == Right(ClientMessage.Ping()))
    },
    test("decode invalid JSON — ошибка") {
      val json = """{"type":"unknown_command"}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result.isLeft)
    },
    test("decode пустой JSON объект — ошибка") {
      val json = """{}"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result.isLeft)
    },
    test("decode невалидный JSON — ошибка") {
      val json = """not a json"""
      val result = json.fromJson[ClientMessage]
      assertTrue(result.isLeft)
    }
  )

  // === ServerMessage — сериализация ===

  private val serverMessagesSuite = suite("ServerMessage")(
    test("encode Connected") {
      val msg: ServerMessage = ServerMessage.Connected("abc-123")
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"connected"""") &&
        json.contains(""""connectionId":"abc-123"""")
      )
    },
    test("encode Subscribed") {
      val msg: ServerMessage = ServerMessage.Subscribed(List(1, 2, 3), 3)
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"subscribed"""") &&
        json.contains(""""vehicleIds":[1,2,3]""") &&
        json.contains(""""total":3""")
      )
    },
    test("encode Unsubscribed") {
      val msg: ServerMessage = ServerMessage.Unsubscribed(List(2), 2)
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"unsubscribed"""") &&
        json.contains(""""total":2""")
      )
    },
    test("encode PositionUpdate") {
      val msg: ServerMessage = ServerMessage.PositionUpdate(
        vehicleId = 12345,
        deviceId = 67890,
        latitude = 55.7558,
        longitude = 37.6173,
        speed = 65,
        course = Some(180),
        satellites = Some(12),
        timestamp = Instant.parse("2026-03-03T10:30:00Z"),
        serverTimestamp = Instant.parse("2026-03-03T10:30:00.123Z")
      )
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"position"""") &&
        json.contains(""""vehicleId":12345""") &&
        json.contains(""""latitude":55.7558""") &&
        json.contains(""""speed":65""")
      )
    },
    test("encode GeoEventNotification") {
      val msg: ServerMessage = ServerMessage.GeoEventNotification(
        eventType = "leave",
        vehicleId = 12345,
        geozoneId = 456,
        geozoneName = "Склад-01",
        latitude = 55.7558,
        longitude = 37.6173,
        speed = 45,
        timestamp = Instant.parse("2026-03-03T10:30:00Z")
      )
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"geoEvent"""") &&
        json.contains(""""eventType":"leave"""") &&
        json.contains(""""geozoneName":"Склад-01"""")
      )
    },
    test("encode SpeedAlert") {
      val msg: ServerMessage = ServerMessage.SpeedAlert(
        vehicleId = 12345,
        currentSpeed = 95,
        maxSpeed = 60,
        latitude = 55.7558,
        longitude = 37.6173,
        timestamp = Instant.parse("2026-03-03T10:30:00Z")
      )
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"speedAlert"""") &&
        json.contains(""""currentSpeed":95""") &&
        json.contains(""""maxSpeed":60""")
      )
    },
    test("encode Pong") {
      val msg: ServerMessage = ServerMessage.Pong()
      val json = msg.toJson
      assertTrue(json.contains(""""type":"pong""""))
    },
    test("encode Error") {
      val msg: ServerMessage = ServerMessage.Error("INVALID_MESSAGE", "Невалидный JSON")
      val json = msg.toJson
      assertTrue(
        json.contains(""""type":"error"""") &&
        json.contains(""""code":"INVALID_MESSAGE"""")
      )
    }
  )

  // === Kafka модели — roundtrip ===

  private val kafkaModelsSuite = suite("Kafka Models")(
    test("GpsPoint — JSON roundtrip") {
      val point = GpsPoint(
        vehicleId = VehicleId(12345),
        deviceId = DeviceId(67890),
        organizationId = OrganizationId(100),
        imei = Imei("352094080055555"),
        latitude = 55.7558,
        longitude = 37.6173,
        altitude = Some(150.0),
        speed = 65,
        course = Some(180),
        satellites = Some(12),
        timestamp = Instant.parse("2026-03-03T10:30:00Z"),
        serverTimestamp = Instant.parse("2026-03-03T10:30:00.123Z"),
        hasGeozones = true,
        hasSpeedRules = false
      )
      val json = point.toJson
      val decoded = json.fromJson[GpsPoint]
      assertTrue(
        decoded.isRight &&
        decoded.toOption.get.vehicleId.value == 12345L &&
        decoded.toOption.get.latitude == 55.7558
      )
    },
    test("GeozoneEvent — JSON roundtrip") {
      val event = GeozoneEvent(
        eventType = GeozoneEventType.Leave,
        vehicleId = VehicleId(12345),
        organizationId = OrganizationId(100),
        geozoneId = GeozoneId(456),
        geozoneName = "Склад-01",
        latitude = 55.7558,
        longitude = 37.6173,
        speed = 45,
        timestamp = Instant.parse("2026-03-03T10:30:00Z")
      )
      val json = event.toJson
      val decoded = json.fromJson[GeozoneEvent]
      assertTrue(
        decoded.isRight &&
        decoded.toOption.get.eventType == GeozoneEventType.Leave &&
        decoded.toOption.get.geozoneName == "Склад-01"
      )
    },
    test("SpeedViolationEvent — JSON roundtrip") {
      val event = SpeedViolationEvent(
        violationType = ViolationType.SpeedLimitExceeded,
        vehicleId = VehicleId(12345),
        organizationId = OrganizationId(100),
        ruleId = Some(SpeedRuleId(789)),
        ruleName = Some("Городской лимит"),
        geozoneId = None,
        geozoneName = None,
        currentSpeed = 95,
        maxSpeed = 60,
        latitude = 55.7558,
        longitude = 37.6173,
        timestamp = Instant.parse("2026-03-03T10:30:00Z")
      )
      val json = event.toJson
      val decoded = json.fromJson[SpeedViolationEvent]
      assertTrue(
        decoded.isRight &&
        decoded.toOption.get.violationType == ViolationType.SpeedLimitExceeded &&
        decoded.toOption.get.currentSpeed == 95
      )
    },
    test("GeozoneEventType — все значения") {
      assertTrue(
        GeozoneEventType.values.length == 2 &&
        GeozoneEventType.values.contains(GeozoneEventType.Enter) &&
        GeozoneEventType.values.contains(GeozoneEventType.Leave)
      )
    },
    test("ViolationType — все значения") {
      assertTrue(
        ViolationType.values.length == 2 &&
        ViolationType.values.contains(ViolationType.SpeedLimitExceeded) &&
        ViolationType.values.contains(ViolationType.GeozoneSpeedLimitExceeded)
      )
    },
    test("GpsPoint — опциональные поля null-safe") {
      val point = GpsPoint(
        vehicleId = VehicleId(1),
        deviceId = DeviceId(1),
        organizationId = OrganizationId(1),
        imei = Imei("000"),
        latitude = 0.0,
        longitude = 0.0,
        altitude = None,
        speed = 0,
        course = None,
        satellites = None,
        timestamp = Instant.now(),
        serverTimestamp = Instant.now(),
        hasGeozones = false,
        hasSpeedRules = false
      )
      val json = point.toJson
      val decoded = json.fromJson[GpsPoint]
      assertTrue(
        decoded.isRight &&
        decoded.toOption.get.altitude.isEmpty &&
        decoded.toOption.get.course.isEmpty &&
        decoded.toOption.get.satellites.isEmpty
      )
    }
  )
