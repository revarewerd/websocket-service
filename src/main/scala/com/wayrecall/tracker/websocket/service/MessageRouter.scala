package com.wayrecall.tracker.websocket.service

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.websocket.domain.*
import com.wayrecall.tracker.websocket.domain.ServerMessage.given

// ============================================================
// МАРШРУТИЗАТОР СООБЩЕНИЙ — Smart Consumer
// ============================================================
// Получает события из Kafka (через consumers), находит подписчиков
// через ConnectionRegistry, применяет throttle и отправляет
// сообщения в WebSocket каналы. Отдельный слой без Kafka зависимости.
// ============================================================

trait MessageRouter:
  /** Маршрутизирует GPS позицию подписчикам */
  def routeGpsEvent(point: GpsPoint): UIO[Unit]

  /** Маршрутизирует событие геозоны подписчикам */
  def routeGeozoneEvent(event: GeozoneEvent): UIO[Unit]

  /** Маршрутизирует нарушение скорости подписчикам */
  def routeSpeedViolation(event: SpeedViolationEvent): UIO[Unit]

object MessageRouter:
  val live: ZLayer[ConnectionRegistry & PositionThrottler, Nothing, MessageRouter] =
    ZLayer.fromFunction(MessageRouterLive(_, _))

private final class MessageRouterLive(
    registry: ConnectionRegistry,
    throttler: PositionThrottler
) extends MessageRouter:

  override def routeGpsEvent(point: GpsPoint): UIO[Unit] =
    for
      // Throttle: не отправлять позицию чаще чем раз в N мс
      shouldSend <- throttler.shouldSend(point.vehicleId)
      _ <- ZIO.when(shouldSend) {
        for
          // Находим подписчиков: прямые (по vehicleId) + org-level
          channels <- registry.getSubscribersForVehicle(point.vehicleId, point.organizationId)
          _ <- ZIO.when(channels.nonEmpty) {
            val message: ServerMessage = ServerMessage.PositionUpdate(
              vehicleId = point.vehicleId.value,
              deviceId = 0L,  // CM не знает deviceId
              latitude = point.latitude,
              longitude = point.longitude,
              speed = point.speed,
              course = Some(point.course),
              satellites = Some(point.satellites),
              timestamp = java.time.Instant.ofEpochMilli(point.deviceTime),
              serverTimestamp = java.time.Instant.ofEpochMilli(point.serverTime)
            )
            sendToChannels(channels, message.toJson)
          }
        yield ()
      }
    yield ()

  override def routeGeozoneEvent(event: GeozoneEvent): UIO[Unit] =
    for
      // Событие геозоны — без throttle, доставляем сразу
      channels <- registry.getSubscribersForVehicle(event.vehicleId, event.organizationId)
      _ <- ZIO.when(channels.nonEmpty) {
        val message: ServerMessage = ServerMessage.GeoEventNotification(
          eventType = event.eventType.toString.toLowerCase,
          vehicleId = event.vehicleId.value,
          geozoneId = event.geozoneId.value,
          geozoneName = event.geozoneName,
          latitude = event.latitude,
          longitude = event.longitude,
          speed = event.speed,
          timestamp = event.timestamp
        )
        sendToChannels(channels, message.toJson)
      }
    yield ()

  override def routeSpeedViolation(event: SpeedViolationEvent): UIO[Unit] =
    for
      // Нарушение скорости — без throttle, доставляем сразу
      channels <- registry.getSubscribersForVehicle(event.vehicleId, event.organizationId)
      _ <- ZIO.when(channels.nonEmpty) {
        val message: ServerMessage = ServerMessage.SpeedAlert(
          vehicleId = event.vehicleId.value,
          currentSpeed = event.currentSpeed,
          maxSpeed = event.maxSpeed,
          latitude = event.latitude,
          longitude = event.longitude,
          timestamp = event.timestamp
        )
        sendToChannels(channels, message.toJson)
      }
    yield ()

  /**
   * Отправляет JSON сообщение во все WS каналы параллельно.
   * Ошибки отдельных каналов логируются, но не прерывают доставку остальным.
   */
  private def sendToChannels(channels: Set[WebSocketChannel], json: String): UIO[Unit] =
    ZIO.foreachParDiscard(channels) { channel =>
      channel.send(ChannelEvent.Read(WebSocketFrame.text(json)))
        .catchAll(e => ZIO.logWarning(s"Ошибка отправки WS сообщения: ${e.getMessage}"))
    }
