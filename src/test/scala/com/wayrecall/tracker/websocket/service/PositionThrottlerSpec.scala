package com.wayrecall.tracker.websocket.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.websocket.domain.*
import com.wayrecall.tracker.websocket.config.ThrottleConfig

// ============================================================
// ТЕСТЫ THROTTLER ПОЗИЦИЙ — ограничение частоты обновлений
// ============================================================
// Используем TestClock для контроля времени.
// config.positionIntervalMs = 1000 → 1 обновление в секунду.
// ============================================================

object PositionThrottlerSpec extends ZIOSpecDefault:

  // Интервал 1 секунда для тестов
  private val throttleLayer =
    ZLayer.succeed(ThrottleConfig(positionIntervalMs = 1000L)) >>> PositionThrottler.live

  def spec = suite("PositionThrottler")(
    test("первый вызов — всегда разрешён") {
      for
        throttler <- ZIO.service[PositionThrottler]
        result    <- throttler.shouldSend(VehicleId(1))
      yield assertTrue(result)
    }.provide(throttleLayer),

    test("повторный вызов сразу — заблокирован") {
      for
        throttler <- ZIO.service[PositionThrottler]
        _         <- throttler.shouldSend(VehicleId(1)) // первый — OK
        result    <- throttler.shouldSend(VehicleId(1)) // повторный — blocked
      yield assertTrue(!result)
    }.provide(throttleLayer),

    test("после прошедшего интервала — снова разрешён") {
      for
        throttler <- ZIO.service[PositionThrottler]
        r1        <- throttler.shouldSend(VehicleId(1))     // true
        r2        <- throttler.shouldSend(VehicleId(1))     // false
        _         <- TestClock.adjust(1001.millis)
        r3        <- throttler.shouldSend(VehicleId(1))     // true (прошло > 1000ms)
      yield assertTrue(r1) && assertTrue(!r2) && assertTrue(r3)
    }.provide(throttleLayer),

    test("разные vehicleId — независимы") {
      for
        throttler <- ZIO.service[PositionThrottler]
        r1        <- throttler.shouldSend(VehicleId(1))     // true
        r2        <- throttler.shouldSend(VehicleId(2))     // true (другой ID)
        r3        <- throttler.shouldSend(VehicleId(1))     // false (слишком рано)
        r4        <- throttler.shouldSend(VehicleId(2))     // false (слишком рано)
      yield assertTrue(r1) && assertTrue(r2) && assertTrue(!r3) && assertTrue(!r4)
    }.provide(throttleLayer),

    test("интервал ровно на границе — тоже заблокирован") {
      for
        throttler <- ZIO.service[PositionThrottler]
        _         <- throttler.shouldSend(VehicleId(1))     // true
        _         <- TestClock.adjust(999.millis)            // 999ms < 1000ms
        result    <- throttler.shouldSend(VehicleId(1))     // false (ещё рано)
      yield assertTrue(!result)
    }.provide(throttleLayer),

    test("после точного интервала — разрешён") {
      for
        throttler <- ZIO.service[PositionThrottler]
        _         <- throttler.shouldSend(VehicleId(1))     // true, lastSent = 0
        _         <- TestClock.adjust(1000.millis)           // now = 1000ms
        result    <- throttler.shouldSend(VehicleId(1))     // true (1000 - 0 >= 1000)
      yield assertTrue(result)
    }.provide(throttleLayer)
  )
