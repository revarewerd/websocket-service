package com.wayrecall.tracker.websocket.service

import zio.*
import com.wayrecall.tracker.websocket.domain.VehicleId
import com.wayrecall.tracker.websocket.config.ThrottleConfig
import java.time.Instant

// ============================================================
// THROTTLER ПОЗИЦИЙ
// ============================================================
// Ограничивает частоту отправки обновлений по каждому vehicleId.
// GPS трекеры шлют данные каждые 1-60 сек, но клиенту достаточно
// 1 обновления в секунду на карте. Экономит трафик WebSocket.
// ============================================================

trait PositionThrottler:
  /** Проверяет, разрешено ли отправить обновление для данного ТС */
  def shouldSend(vehicleId: VehicleId): UIO[Boolean]

object PositionThrottler:
  val live: ZLayer[ThrottleConfig, Nothing, PositionThrottler] =
    ZLayer.fromZIO(
      for
        config   <- ZIO.service[ThrottleConfig]
        lastSent <- Ref.make(Map.empty[Long, Instant])
      yield PositionThrottlerLive(config, lastSent)
    )

private final class PositionThrottlerLive(
    config: ThrottleConfig,
    lastSent: Ref[Map[Long, Instant]]
) extends PositionThrottler:

  override def shouldSend(vehicleId: VehicleId): UIO[Boolean] =
    val key = vehicleId.value
    val minIntervalMs = config.positionIntervalMs

    Clock.instant.flatMap { now =>
      lastSent.modify { map =>
        map.get(key) match
          case Some(last) if java.time.Duration.between(last, now).toMillis < minIntervalMs =>
            // Слишком рано — пропускаем
            (false, map)
          case _ =>
            // Разрешаем отправку, обновляем timestamp
            (true, map + (key -> now))
      }
    }
