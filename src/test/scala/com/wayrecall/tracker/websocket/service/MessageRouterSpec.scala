package com.wayrecall.tracker.websocket.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.websocket.domain.*
import com.wayrecall.tracker.websocket.domain.ServerMessage.given
import java.time.Instant
import java.util.UUID

// ============================================================
// ТЕСТЫ МАРШРУТИЗАТОРА СООБЩЕНИЙ
// ============================================================
// Используем мок-реализации ConnectionRegistry и PositionThrottler.
// Мок канал записывает отправленные JSON сообщения в Ref[List[String]].
// ============================================================

object MessageRouterSpec extends ZIOSpecDefault:

  // --- Мок WebSocketChannel: записывает отправленные сообщения ---
  private def makeCaptureChannel(sentMessages: Ref[List[String]]): WebSocketChannel =
    new Channel[ChannelEvent[WebSocketFrame], ChannelEvent[WebSocketFrame]]:
      override def awaitShutdown(implicit trace: Trace): UIO[Unit] = ZIO.unit
      override def receive(implicit trace: Trace): Task[ChannelEvent[WebSocketFrame]] = ZIO.never
      override def receiveAll[Env, Err](
          handler: ChannelEvent[WebSocketFrame] => ZIO[Env, Err, Any]
      )(implicit trace: Trace): ZIO[Env, Err, Unit] = ZIO.unit
      override def send(event: ChannelEvent[WebSocketFrame])(implicit trace: Trace): Task[Unit] =
        event match
          case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
            sentMessages.update(_ :+ text)
          case _ => ZIO.unit
      override def sendAll(events: Iterable[ChannelEvent[WebSocketFrame]])(implicit trace: Trace): Task[Unit] =
        ZIO.foreachDiscard(events)(send(_))
      override def shutdown(implicit trace: Trace): UIO[Unit] = ZIO.unit

  // --- Мок ConnectionRegistry: возвращает заранее заданные каналы ---
  private class MockRegistry(channelsRef: Ref[Set[WebSocketChannel]]) extends ConnectionRegistry:
    override def register(organizationId: OrganizationId, channel: WebSocketChannel): UIO[UUID] =
      ZIO.succeed(UUID.randomUUID())
    override def unregister(connectionId: UUID): UIO[Unit] = ZIO.unit
    override def subscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int] =
      ZIO.succeed(0)
    override def unsubscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int] =
      ZIO.succeed(0)
    override def subscribeOrg(connectionId: UUID): UIO[Unit] = ZIO.unit
    override def unsubscribeAll(connectionId: UUID): UIO[Unit] = ZIO.unit
    override def getSubscribersForVehicle(vehicleId: VehicleId, organizationId: OrganizationId): UIO[Set[WebSocketChannel]] =
      channelsRef.get
    override def connectionCount: UIO[Int] = ZIO.succeed(0)
    override def subscriptionCount: UIO[Int] = ZIO.succeed(0)

  // --- Мок PositionThrottler: управляемый результат ---
  private class MockThrottler(allowed: Boolean) extends PositionThrottler:
    override def shouldSend(vehicleId: VehicleId): UIO[Boolean] =
      ZIO.succeed(allowed)

  // --- Фабрика через ZLayer (MessageRouterLive — private, создаём через live layer) ---

  private def routerLayer(
      channelsRef: Ref[Set[WebSocketChannel]],
      throttleAllowed: Boolean
  ): ZLayer[Any, Nothing, MessageRouter] =
    (ZLayer.succeed(MockRegistry(channelsRef): ConnectionRegistry) ++
     ZLayer.succeed(MockThrottler(throttleAllowed): PositionThrottler)) >>>
    MessageRouter.live

  // --- Тестовые данные ---

  private val testPoint = GpsPoint(
    vehicleId = VehicleId(12345),
    organizationId = OrganizationId(100),
    imei = Imei("352094080055555"),
    latitude = 55.7558,
    longitude = 37.6173,
    altitude = 150,
    speed = 65,
    course = 180,
    satellites = 12,
    deviceTime = Instant.parse("2026-03-03T10:30:00Z").toEpochMilli,
    serverTime = Instant.parse("2026-03-03T10:30:00.123Z").toEpochMilli,
    hasGeozones = true,
    hasSpeedRules = false,
    hasRetranslation = false,
    retranslationTargets = None,
    isMoving = true,
    isValid = true,
    protocol = "wialon",
    instanceId = "cm-test"
  )

  private val testGeozoneEvent = GeozoneEvent(
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

  private val testSpeedViolation = SpeedViolationEvent(
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

  def spec = suite("MessageRouter")(
    gpsRoutingSuite,
    geozoneRoutingSuite,
    speedRoutingSuite,
    noSubscribersSuite
  )

  // === GPS Event Routing ===

  private val gpsRoutingSuite = suite("routeGpsEvent")(
    test("GPS точка → PositionUpdate для подписчиков") {
      for
        sentRef     <- Ref.make(List.empty[String])
        ch           = makeCaptureChannel(sentRef)
        channelsRef <- Ref.make(Set[WebSocketChannel](ch))
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, true))
        _           <- router.routeGpsEvent(testPoint)
        sent        <- sentRef.get
      yield
        assertTrue(sent.size == 1) &&
        assertTrue(sent.head.contains(""""type":"position"""")) &&
        assertTrue(sent.head.contains(""""vehicleId":12345""")) &&
        assertTrue(sent.head.contains(""""latitude":55.7558"""))
    },

    test("GPS точка при throttle=false — НЕ отправляется") {
      for
        sentRef     <- Ref.make(List.empty[String])
        ch           = makeCaptureChannel(sentRef)
        channelsRef <- Ref.make(Set[WebSocketChannel](ch))
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, false))
        _           <- router.routeGpsEvent(testPoint)
        sent        <- sentRef.get
      yield assertTrue(sent.isEmpty)
    },

    test("GPS точка → доставка нескольким подписчикам") {
      for
        sentRef1    <- Ref.make(List.empty[String])
        sentRef2    <- Ref.make(List.empty[String])
        ch1          = makeCaptureChannel(sentRef1)
        ch2          = makeCaptureChannel(sentRef2)
        channelsRef <- Ref.make(Set[WebSocketChannel](ch1, ch2))
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, true))
        _           <- router.routeGpsEvent(testPoint)
        sent1       <- sentRef1.get
        sent2       <- sentRef2.get
      yield assertTrue(sent1.size == 1) && assertTrue(sent2.size == 1)
    }
  )

  // === Geozone Event Routing ===

  private val geozoneRoutingSuite = suite("routeGeozoneEvent")(
    test("событие геозоны → GeoEventNotification (без throttle)") {
      for
        sentRef     <- Ref.make(List.empty[String])
        ch           = makeCaptureChannel(sentRef)
        channelsRef <- Ref.make(Set[WebSocketChannel](ch))
        // throttle=false, но геозоны не проходят через throttle
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, false))
        _           <- router.routeGeozoneEvent(testGeozoneEvent)
        sent        <- sentRef.get
      yield
        assertTrue(sent.size == 1) &&
        assertTrue(sent.head.contains(""""type":"geoEvent"""")) &&
        assertTrue(sent.head.contains(""""eventType":"leave"""")) &&
        assertTrue(sent.head.contains(""""geozoneName":"Склад-01""""))
    }
  )

  // === Speed Violation Routing ===

  private val speedRoutingSuite = suite("routeSpeedViolation")(
    test("нарушение скорости → SpeedAlert (без throttle)") {
      for
        sentRef     <- Ref.make(List.empty[String])
        ch           = makeCaptureChannel(sentRef)
        channelsRef <- Ref.make(Set[WebSocketChannel](ch))
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, false))
        _           <- router.routeSpeedViolation(testSpeedViolation)
        sent        <- sentRef.get
      yield
        assertTrue(sent.size == 1) &&
        assertTrue(sent.head.contains(""""type":"speedAlert"""")) &&
        assertTrue(sent.head.contains(""""currentSpeed":95""")) &&
        assertTrue(sent.head.contains(""""maxSpeed":60"""))
    }
  )

  // === Нет подписчиков ===

  private val noSubscribersSuite = suite("no subscribers")(
    test("GPS точка без подписчиков — ничего не отправляется") {
      for
        channelsRef <- Ref.make(Set.empty[WebSocketChannel])
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, true))
        _           <- router.routeGpsEvent(testPoint)
        // Нет канала — нет ошибки, просто тихо пропускаем
      yield assertTrue(true)
    },

    test("геозона без подписчиков — ничего не отправляется") {
      for
        channelsRef <- Ref.make(Set.empty[WebSocketChannel])
        router      <- ZIO.service[MessageRouter].provideLayer(routerLayer(channelsRef, true))
        _           <- router.routeGeozoneEvent(testGeozoneEvent)
      yield assertTrue(true)
    }
  )
