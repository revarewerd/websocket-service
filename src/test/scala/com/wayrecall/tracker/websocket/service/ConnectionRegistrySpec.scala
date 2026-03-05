package com.wayrecall.tracker.websocket.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.http.*
import com.wayrecall.tracker.websocket.domain.*

// ============================================================
// ТЕСТЫ РЕЕСТРА СОЕДИНЕНИЙ — in-memory state management
// ============================================================

object ConnectionRegistrySpec extends ZIOSpecDefault:

  // --- Мок WebSocketChannel: ничего не делает, нужен только как ссылка ---
  private def makeNoOpChannel: UIO[WebSocketChannel] =
    Queue.unbounded[ChannelEvent[WebSocketFrame]].map { queue =>
      new Channel[ChannelEvent[WebSocketFrame], ChannelEvent[WebSocketFrame]]:
        override def awaitShutdown(implicit trace: Trace): UIO[Unit] = ZIO.unit
        override def receive(implicit trace: Trace): Task[ChannelEvent[WebSocketFrame]] = queue.take
        override def receiveAll[Env, Err](
            handler: ChannelEvent[WebSocketFrame] => ZIO[Env, Err, Any]
        )(implicit trace: Trace): ZIO[Env, Err, Unit] = ZIO.unit
        override def send(event: ChannelEvent[WebSocketFrame])(implicit trace: Trace): Task[Unit] = ZIO.unit
        override def sendAll(events: Iterable[ChannelEvent[WebSocketFrame]])(implicit trace: Trace): Task[Unit] = ZIO.unit
        override def shutdown(implicit trace: Trace): UIO[Unit] = ZIO.unit
    }

  // Фабрика: создаём реестр через live layer
  private val registryLayer = ConnectionRegistry.live

  def spec = suite("ConnectionRegistry")(
    registerSuite,
    unregisterSuite,
    subscribeVehiclesSuite,
    subscribeOrgSuite,
    getSubscribersSuite,
    statisticsSuite
  )

  // === Регистрация ===

  private val registerSuite = suite("register")(
    test("регистрация возвращает уникальный UUID") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch1      <- makeNoOpChannel
        ch2      <- makeNoOpChannel
        id1      <- registry.register(OrganizationId(1), ch1)
        id2      <- registry.register(OrganizationId(1), ch2)
      yield assertTrue(id1 != id2)
    }.provide(registryLayer),

    test("регистрация увеличивает счётчик соединений") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        before   <- registry.connectionCount
        _        <- registry.register(OrganizationId(1), ch)
        after    <- registry.connectionCount
      yield assertTrue(before == 0) && assertTrue(after == 1)
    }.provide(registryLayer),

    test("несколько регистраций из разных организаций") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch1      <- makeNoOpChannel
        ch2      <- makeNoOpChannel
        ch3      <- makeNoOpChannel
        _        <- registry.register(OrganizationId(1), ch1)
        _        <- registry.register(OrganizationId(2), ch2)
        _        <- registry.register(OrganizationId(1), ch3)
        count    <- registry.connectionCount
      yield assertTrue(count == 3)
    }.provide(registryLayer)
  )

  // === Удаление регистрации ===

  private val unregisterSuite = suite("unregister")(
    test("удаление уменьшает счётчик") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        before   <- registry.connectionCount
        _        <- registry.unregister(id)
        after    <- registry.connectionCount
      yield assertTrue(before == 1) && assertTrue(after == 0)
    }.provide(registryLayer),

    test("удаление несуществующего ID — без ошибки") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        _        <- registry.unregister(java.util.UUID.randomUUID())
        count    <- registry.connectionCount
      yield assertTrue(count == 0)
    }.provide(registryLayer),

    test("удаление очищает vehicle subscriptions из индекса") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        _        <- registry.subscribeVehicles(id, Set(VehicleId(100), VehicleId(200)))
        subsBefore <- registry.subscriptionCount
        _        <- registry.unregister(id)
        subsAfter  <- registry.subscriptionCount
      yield assertTrue(subsBefore == 2) && assertTrue(subsAfter == 0)
    }.provide(registryLayer),

    test("удаление очищает org подписку") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        _        <- registry.subscribeOrg(id)
        _        <- registry.unregister(id)
        // После удаления org подписчиков не должно быть
        channels <- registry.getSubscribersForVehicle(VehicleId(999), OrganizationId(1))
      yield assertTrue(channels.isEmpty)
    }.provide(registryLayer)
  )

  // === Подписка на конкретные ТС ===

  private val subscribeVehiclesSuite = suite("subscribeVehicles")(
    test("возвращает общее количество подписок") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        total    <- registry.subscribeVehicles(id, Set(VehicleId(1), VehicleId(2), VehicleId(3)))
      yield assertTrue(total == 3)
    }.provide(registryLayer),

    test("повторная подписка на те же ТС — не дублирует") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        _        <- registry.subscribeVehicles(id, Set(VehicleId(1), VehicleId(2)))
        total    <- registry.subscribeVehicles(id, Set(VehicleId(2), VehicleId(3)))
      yield assertTrue(total == 3) // {1, 2, 3} — Set объединение
    }.provide(registryLayer),

    test("unsubscribeVehicles уменьшает количество") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        _        <- registry.subscribeVehicles(id, Set(VehicleId(1), VehicleId(2), VehicleId(3)))
        total    <- registry.unsubscribeVehicles(id, Set(VehicleId(2)))
      yield assertTrue(total == 2) // {1, 3}
    }.provide(registryLayer)
  )

  // === Подписка на организацию ===

  private val subscribeOrgSuite = suite("subscribeOrg")(
    test("подписка на организацию — виден любой vehicleId") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(42), ch)
        _        <- registry.subscribeOrg(id)
        // Любой vehicleId из org=42 должен найти этот канал
        channels <- registry.getSubscribersForVehicle(VehicleId(999), OrganizationId(42))
      yield assertTrue(channels.size == 1)
    }.provide(registryLayer),

    test("повторная подписка на org — идемпотентна") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(42), ch)
        _        <- registry.subscribeOrg(id)
        _        <- registry.subscribeOrg(id) // повторно
        channels <- registry.getSubscribersForVehicle(VehicleId(999), OrganizationId(42))
      yield assertTrue(channels.size == 1) // не дублируется
    }.provide(registryLayer),

    test("unsubscribeAll очищает и vehicle и org подписки") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(42), ch)
        _        <- registry.subscribeVehicles(id, Set(VehicleId(1)))
        _        <- registry.subscribeOrg(id)
        _        <- registry.unsubscribeAll(id)
        subs     <- registry.subscriptionCount
        channels <- registry.getSubscribersForVehicle(VehicleId(1), OrganizationId(42))
      yield assertTrue(subs == 0) && assertTrue(channels.isEmpty)
    }.provide(registryLayer)
  )

  // === Поиск подписчиков ===

  private val getSubscribersSuite = suite("getSubscribersForVehicle")(
    test("объединение vehicle + org подписчиков (Dual Index)") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch1      <- makeNoOpChannel
        ch2      <- makeNoOpChannel
        // ch1 подписан на конкретное ТС
        id1      <- registry.register(OrganizationId(1), ch1)
        _        <- registry.subscribeVehicles(id1, Set(VehicleId(100)))
        // ch2 подписан на всю организацию
        id2      <- registry.register(OrganizationId(1), ch2)
        _        <- registry.subscribeOrg(id2)
        // Оба должны получить обновление для vehicleId=100
        channels <- registry.getSubscribersForVehicle(VehicleId(100), OrganizationId(1))
      yield assertTrue(channels.size == 2)
    }.provide(registryLayer),

    test("нет подписчиков — пустой Set") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        channels <- registry.getSubscribersForVehicle(VehicleId(999), OrganizationId(999))
      yield assertTrue(channels.isEmpty)
    }.provide(registryLayer),

    test("org подписчик другой организации — НЕ видит чужие ТС") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch       <- makeNoOpChannel
        id       <- registry.register(OrganizationId(1), ch)
        _        <- registry.subscribeOrg(id)
        // ТС из org=2 — не должен видеть подписчик org=1
        channels <- registry.getSubscribersForVehicle(VehicleId(100), OrganizationId(2))
      yield assertTrue(channels.isEmpty)
    }.provide(registryLayer)
  )

  // === Статистика ===

  private val statisticsSuite = suite("statistics")(
    test("connectionCount и subscriptionCount корректны") {
      for
        registry <- ZIO.service[ConnectionRegistry]
        ch1      <- makeNoOpChannel
        ch2      <- makeNoOpChannel
        id1      <- registry.register(OrganizationId(1), ch1)
        id2      <- registry.register(OrganizationId(2), ch2)
        _        <- registry.subscribeVehicles(id1, Set(VehicleId(1), VehicleId(2)))
        _        <- registry.subscribeVehicles(id2, Set(VehicleId(3)))
        conns    <- registry.connectionCount
        subs     <- registry.subscriptionCount
      yield assertTrue(conns == 2) && assertTrue(subs == 3)
    }.provide(registryLayer)
  )
