package com.wayrecall.tracker.websocket.domain

import zio.json.*
import java.time.Instant

// ============================================================
// WS ПРОТОКОЛ — СООБЩЕНИЯ КЛИЕНТ ↔ СЕРВЕР
// ============================================================
// Клиент отправляет команды подписки/отписки/ping.
// Сервер отправляет подтверждения, позиции, события, ошибки.
// Дискриминатор: поле "type" в JSON.
// ============================================================

// === Сообщения клиент → сервер ===

@jsonDiscriminator("type")
sealed trait ClientMessage

object ClientMessage:
  /** Подписка на конкретные ТС по vehicleId */
  @jsonHint("subscribe")
  final case class Subscribe(vehicleIds: List[Long]) extends ClientMessage

  /** Подписка на ВСЕ ТС организации пользователя */
  @jsonHint("subscribeOrg")
  final case class SubscribeOrg() extends ClientMessage

  /** Отписка от конкретных ТС */
  @jsonHint("unsubscribe")
  final case class Unsubscribe(vehicleIds: List[Long]) extends ClientMessage

  /** Отписка от всех подписок */
  @jsonHint("unsubscribeAll")
  final case class UnsubscribeAll() extends ClientMessage

  /** Heartbeat ping */
  @jsonHint("ping")
  final case class Ping() extends ClientMessage

  given JsonDecoder[ClientMessage] = DeriveJsonDecoder.gen[ClientMessage]

// === Сообщения сервер → клиент ===

@jsonDiscriminator("type")
sealed trait ServerMessage

object ServerMessage:
  /** Подтверждение подключения */
  @jsonHint("connected")
  final case class Connected(connectionId: String) extends ServerMessage

  /** Подтверждение подписки */
  @jsonHint("subscribed")
  final case class Subscribed(vehicleIds: List[Long], total: Int) extends ServerMessage

  /** Подтверждение отписки */
  @jsonHint("unsubscribed")
  final case class Unsubscribed(vehicleIds: List[Long], total: Int) extends ServerMessage

  /** Обновление позиции ТС */
  @jsonHint("position")
  final case class PositionUpdate(
      vehicleId: Long,
      deviceId: Long,
      latitude: Double,
      longitude: Double,
      speed: Int,
      course: Option[Int],
      satellites: Option[Int],
      timestamp: Instant,
      serverTimestamp: Instant
  ) extends ServerMessage

  /** Событие геозоны (enter/leave) */
  @jsonHint("geoEvent")
  final case class GeoEventNotification(
      eventType: String,
      vehicleId: Long,
      geozoneId: Long,
      geozoneName: String,
      latitude: Double,
      longitude: Double,
      speed: Int,
      timestamp: Instant
  ) extends ServerMessage

  /** Алерт превышения скорости */
  @jsonHint("speedAlert")
  final case class SpeedAlert(
      vehicleId: Long,
      currentSpeed: Int,
      maxSpeed: Int,
      latitude: Double,
      longitude: Double,
      timestamp: Instant
  ) extends ServerMessage

  /** Heartbeat pong */
  @jsonHint("pong")
  final case class Pong() extends ServerMessage

  /** Ошибка */
  @jsonHint("error")
  final case class Error(code: String, message: String) extends ServerMessage

  given JsonEncoder[ServerMessage] = DeriveJsonEncoder.gen[ServerMessage]
