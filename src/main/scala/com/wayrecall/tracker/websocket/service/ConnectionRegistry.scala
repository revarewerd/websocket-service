package com.wayrecall.tracker.websocket.service

import zio.*
import zio.http.WebSocketChannel
import com.wayrecall.tracker.websocket.domain.*
import java.time.Instant
import java.util.UUID

// ============================================================
// РЕЕСТР СОЕДИНЕНИЙ — Smart Consumer Pattern
// ============================================================
// In-memory хранение активных WS соединений и подписок.
// Два индекса:
//   1. vehicleSubscriptions: vehicleId → Set[connId]
//   2. orgSubscriptions: orgId → Set[connId]
// При получении GPS точки: объединяем обе коллекции подписчиков.
// При отключении: автоматическая очистка всех индексов.
// ============================================================

/** Активное WebSocket соединение */
final case class ActiveConnection(
    id: UUID,
    organizationId: OrganizationId,
    channel: WebSocketChannel,
    subscribedVehicles: Set[VehicleId],
    subscribedToOrg: Boolean,
    connectedAt: Instant
)

trait ConnectionRegistry:
  // --- Управление соединениями ---
  def register(organizationId: OrganizationId, channel: WebSocketChannel): UIO[UUID]
  def unregister(connectionId: UUID): UIO[Unit]

  // --- Подписки на конкретные ТС ---
  def subscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int]
  def unsubscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int]

  // --- Подписка на всю организацию ---
  def subscribeOrg(connectionId: UUID): UIO[Unit]

  // --- Отписка от всего ---
  def unsubscribeAll(connectionId: UUID): UIO[Unit]

  // --- Поиск подписчиков для роутинга сообщений ---
  def getSubscribersForVehicle(vehicleId: VehicleId, organizationId: OrganizationId): UIO[Set[WebSocketChannel]]

  // --- Статистика ---
  def connectionCount: UIO[Int]
  def subscriptionCount: UIO[Int]

object ConnectionRegistry:
  val live: ZLayer[Any, Nothing, ConnectionRegistry] =
    ZLayer.fromZIO(
      for
        connections       <- Ref.make(Map.empty[UUID, ActiveConnection])
        vehicleIndex      <- Ref.make(Map.empty[Long, Set[UUID]])
        orgIndex          <- Ref.make(Map.empty[Long, Set[UUID]])
      yield ConnectionRegistryLive(connections, vehicleIndex, orgIndex)
    )

private final class ConnectionRegistryLive(
    connections: Ref[Map[UUID, ActiveConnection]],
    vehicleIndex: Ref[Map[Long, Set[UUID]]],
    orgIndex: Ref[Map[Long, Set[UUID]]]
) extends ConnectionRegistry:

  override def register(organizationId: OrganizationId, channel: WebSocketChannel): UIO[UUID] =
    val connId = UUID.randomUUID()
    val conn = ActiveConnection(
      id = connId,
      organizationId = organizationId,
      channel = channel,
      subscribedVehicles = Set.empty,
      subscribedToOrg = false,
      connectedAt = Instant.now()
    )
    connections.update(_ + (connId -> conn)) *>
    ZIO.logInfo(s"WS соединение зарегистрировано: $connId (org=${organizationId.value})").as(connId)

  override def unregister(connectionId: UUID): UIO[Unit] =
    for
      maybeConn <- connections.modify { map =>
        (map.get(connectionId), map - connectionId)
      }
      _ <- maybeConn match
        case Some(conn) =>
          // Удаляем из vehicle index
          ZIO.foreachDiscard(conn.subscribedVehicles) { vid =>
            vehicleIndex.update(removeFromIndex(_, vid.value, connectionId))
          } *>
          // Удаляем из org index
          ZIO.when(conn.subscribedToOrg) {
            orgIndex.update(removeFromIndex(_, conn.organizationId.value, connectionId))
          } *>
          ZIO.logInfo(s"WS соединение удалено: $connectionId")
        case None =>
          ZIO.unit
    yield ()

  override def subscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int] =
    for
      // Обновляем соединение — добавляем vehicleIds в подписки
      _ <- connections.update(_.updatedWith(connectionId)(_.map(c =>
        c.copy(subscribedVehicles = c.subscribedVehicles ++ vehicleIds)
      )))
      // Добавляем в обратный индекс vehicle → connections
      _ <- ZIO.foreachDiscard(vehicleIds) { vid =>
        vehicleIndex.update(addToIndex(_, vid.value, connectionId))
      }
      // Возвращаем общее количество подписок
      total <- connections.get.map(
        _.get(connectionId).map(_.subscribedVehicles.size).getOrElse(0)
      )
    yield total

  override def unsubscribeVehicles(connectionId: UUID, vehicleIds: Set[VehicleId]): UIO[Int] =
    for
      _ <- connections.update(_.updatedWith(connectionId)(_.map(c =>
        c.copy(subscribedVehicles = c.subscribedVehicles -- vehicleIds)
      )))
      _ <- ZIO.foreachDiscard(vehicleIds) { vid =>
        vehicleIndex.update(removeFromIndex(_, vid.value, connectionId))
      }
      total <- connections.get.map(
        _.get(connectionId).map(_.subscribedVehicles.size).getOrElse(0)
      )
    yield total

  override def subscribeOrg(connectionId: UUID): UIO[Unit] =
    for
      maybeConn <- connections.get.map(_.get(connectionId))
      _ <- maybeConn match
        case Some(conn) if !conn.subscribedToOrg =>
          connections.update(_.updatedWith(connectionId)(_.map(_.copy(subscribedToOrg = true)))) *>
          orgIndex.update(addToIndex(_, conn.organizationId.value, connectionId)) *>
          ZIO.logInfo(s"WS $connectionId подписан на org=${conn.organizationId.value}")
        case _ =>
          ZIO.unit // уже подписан или соединение не найдено
    yield ()

  override def unsubscribeAll(connectionId: UUID): UIO[Unit] =
    for
      maybeConn <- connections.get.map(_.get(connectionId))
      _ <- maybeConn match
        case Some(conn) =>
          // Очищаем vehicle index
          ZIO.foreachDiscard(conn.subscribedVehicles) { vid =>
            vehicleIndex.update(removeFromIndex(_, vid.value, connectionId))
          } *>
          // Очищаем org index
          ZIO.when(conn.subscribedToOrg) {
            orgIndex.update(removeFromIndex(_, conn.organizationId.value, connectionId))
          } *>
          // Сбрасываем подписки в соединении
          connections.update(_.updatedWith(connectionId)(_.map(
            _.copy(subscribedVehicles = Set.empty, subscribedToOrg = false)
          )))
        case None =>
          ZIO.unit
    yield ()

  override def getSubscribersForVehicle(
      vehicleId: VehicleId,
      organizationId: OrganizationId
  ): UIO[Set[WebSocketChannel]] =
    for
      // Прямые подписчики на конкретное ТС
      vehicleConnIds <- vehicleIndex.get.map(_.getOrElse(vehicleId.value, Set.empty))
      // Подписчики на всю организацию (видят все ТС)
      orgConnIds <- orgIndex.get.map(_.getOrElse(organizationId.value, Set.empty))
      // Объединяем и получаем WebSocket каналы
      allConnIds = vehicleConnIds ++ orgConnIds
      conns <- connections.get
      channels = allConnIds.flatMap(id => conns.get(id).map(_.channel))
    yield channels

  override def connectionCount: UIO[Int] =
    connections.get.map(_.size)

  override def subscriptionCount: UIO[Int] =
    vehicleIndex.get.map(_.values.map(_.size).sum)

  // --- Вспомогательные методы для работы с индексами ---

  private def addToIndex(idx: Map[Long, Set[UUID]], key: Long, connId: UUID): Map[Long, Set[UUID]] =
    idx.updatedWith(key) {
      case Some(set) => Some(set + connId)
      case None      => Some(Set(connId))
    }

  private def removeFromIndex(idx: Map[Long, Set[UUID]], key: Long, connId: UUID): Map[Long, Set[UUID]] =
    idx.updatedWith(key)(_.map(_ - connId).filter(_.nonEmpty))
