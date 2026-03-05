package com.wayrecall.tracker.websocket.domain

import zio.json.*
import java.time.Instant

// ============================================================
// ДОМЕННЫЕ СУЩНОСТИ — WEBSOCKET SERVICE
// ============================================================
// Opaque types совместимы с Connection Manager и Rule Checker.
// Подписки ведутся по vehicleId (бизнес-сущность для пользователя).
// ============================================================

// === Opaque Types ===

opaque type VehicleId = Long
object VehicleId:
  def apply(value: Long): VehicleId = value
  extension (id: VehicleId) def value: Long = id
  given JsonCodec[VehicleId] = JsonCodec.long.transform(VehicleId.apply, _.value)

opaque type DeviceId = Long
object DeviceId:
  def apply(value: Long): DeviceId = value
  extension (id: DeviceId) def value: Long = id
  given JsonCodec[DeviceId] = JsonCodec.long.transform(DeviceId.apply, _.value)

opaque type OrganizationId = Long
object OrganizationId:
  def apply(value: Long): OrganizationId = value
  extension (id: OrganizationId) def value: Long = id
  given JsonCodec[OrganizationId] = JsonCodec.long.transform(OrganizationId.apply, _.value)

opaque type GeozoneId = Long
object GeozoneId:
  def apply(value: Long): GeozoneId = value
  extension (id: GeozoneId) def value: Long = id
  given JsonCodec[GeozoneId] = JsonCodec.long.transform(GeozoneId.apply, _.value)

opaque type Imei = String
object Imei:
  def apply(value: String): Imei = value
  extension (imei: Imei) def value: String = imei
  given JsonCodec[Imei] = JsonCodec.string.transform(Imei.apply, _.value)

opaque type SpeedRuleId = Long
object SpeedRuleId:
  def apply(value: Long): SpeedRuleId = value
  extension (id: SpeedRuleId) def value: Long = id
  given JsonCodec[SpeedRuleId] = JsonCodec.long.transform(SpeedRuleId.apply, _.value)

// === Kafka входящие структуры ===

/**
 * GPS точка из топика gps-events.
 * Формат совместим с Connection Manager (публикует) и Rule Checker (потребляет).
 */
final case class GpsPoint(
    vehicleId: VehicleId,
    deviceId: DeviceId,
    organizationId: OrganizationId,
    imei: Imei,
    latitude: Double,
    longitude: Double,
    altitude: Option[Double],
    speed: Int,
    course: Option[Int],
    satellites: Option[Int],
    timestamp: Instant,
    serverTimestamp: Instant,
    hasGeozones: Boolean,
    hasSpeedRules: Boolean
) derives JsonCodec

/**
 * Тип события геозоны (enter/leave) из топика geozone-events.
 */
enum GeozoneEventType derives JsonCodec:
  case Enter, Leave

/**
 * Событие геозоны из топика geozone-events (от Rule Checker).
 */
final case class GeozoneEvent(
    eventType: GeozoneEventType,
    vehicleId: VehicleId,
    organizationId: OrganizationId,
    geozoneId: GeozoneId,
    geozoneName: String,
    latitude: Double,
    longitude: Double,
    speed: Int,
    timestamp: Instant
) derives JsonCodec

/**
 * Тип нарушения скорости из топика rule-violations.
 */
enum ViolationType derives JsonCodec:
  case SpeedLimitExceeded
  case GeozoneSpeedLimitExceeded

/**
 * Нарушение скорости из топика rule-violations (от Rule Checker).
 */
final case class SpeedViolationEvent(
    violationType: ViolationType,
    vehicleId: VehicleId,
    organizationId: OrganizationId,
    ruleId: Option[SpeedRuleId],
    ruleName: Option[String],
    geozoneId: Option[GeozoneId],
    geozoneName: Option[String],
    currentSpeed: Int,
    maxSpeed: Int,
    latitude: Double,
    longitude: Double,
    timestamp: Instant
) derives JsonCodec
