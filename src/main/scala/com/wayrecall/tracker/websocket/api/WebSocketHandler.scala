package com.wayrecall.tracker.websocket.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.websocket.domain.*
import com.wayrecall.tracker.websocket.domain.ClientMessage.given
import com.wayrecall.tracker.websocket.domain.ServerMessage.given
import com.wayrecall.tracker.websocket.service.ConnectionRegistry
import java.util.UUID

// ============================================================
// WEBSOСKET HANDLER
// ============================================================
// Обрабатывает WS подключения:
//   1. Извлекает orgId из query params (MVP, потом — JWT)
//   2. Регистрирует соединение в ConnectionRegistry
//   3. Слушает команды от клиента (subscribe/unsubscribe/ping)
//   4. При отключении — автоматическая очистка через ensuring
//
// URL: ws://host:8090/ws?orgId=123
// ============================================================

object WebSocketHandler:

  def routes: Routes[ConnectionRegistry, Nothing] =
    Routes(
      // WS endpoint: ws://host:8090/ws?orgId=123
      // TODO: заменить orgId на JWT аутентификацию через API Gateway
      Method.GET / "ws" -> handler { (req: Request) =>
        val orgIdOpt = req.url.queryParams.queryParam("orgId")
          .flatMap(s => scala.util.Try(s.toLong).toOption)

        orgIdOpt match
          case None =>
            ZIO.succeed(Response.text("Отсутствует параметр orgId").status(Status.BadRequest))

          case Some(orgIdLong) =>
            makeWebSocketApp(OrganizationId(orgIdLong)).toResponse
      }
    )

  /**
   * Создаёт WebSocketApp для конкретной организации.
   * Lifecycle: register → listen → ensuring(unregister)
   */
  private def makeWebSocketApp(orgId: OrganizationId): WebSocketApp[ConnectionRegistry] =
    Handler.webSocket { channel =>
      for
        registry <- ZIO.service[ConnectionRegistry]

        // Регистрируем соединение
        connId <- registry.register(orgId, channel)

        // Отправляем подтверждение подключения
        connectedMsg: ServerMessage = ServerMessage.Connected(connId.toString)
        _ <- channel.send(ChannelEvent.Read(WebSocketFrame.text(connectedMsg.toJson)))

        // Слушаем входящие сообщения до отключения
        _ <- channel.receiveAll {
          case ChannelEvent.Read(WebSocketFrame.Text(text)) =>
            handleClientMessage(connId, text, registry, channel)

          case ChannelEvent.Read(WebSocketFrame.Close(_, _)) =>
            ZIO.logInfo(s"WS клиент закрыл соединение: $connId")

          case ChannelEvent.Unregistered =>
            ZIO.logInfo(s"WS канал отключён: $connId")

          case _ =>
            ZIO.unit
        }.ensuring(
          // Гарантированная очистка при любом завершении
          registry.unregister(connId) *>
          ZIO.logInfo(s"WS очистка завершена: $connId")
        )
      yield ()
    }

  /**
   * Обработка входящего сообщения от клиента.
   * Парсит JSON, выполняет команду, отправляет подтверждение.
   */
  private def handleClientMessage(
      connId: UUID,
      text: String,
      registry: ConnectionRegistry,
      channel: WebSocketChannel
  ): UIO[Unit] =
    text.fromJson[ClientMessage] match
      case Right(ClientMessage.Subscribe(vehicleIds)) =>
        for
          total <- registry.subscribeVehicles(connId, vehicleIds.map(VehicleId(_)).toSet)
          _ <- sendMessage(channel, ServerMessage.Subscribed(vehicleIds, total))
          _ <- ZIO.logInfo(s"WS $connId подписан на ${vehicleIds.size} ТС (всего=$total)")
        yield ()

      case Right(ClientMessage.SubscribeOrg()) =>
        for
          _ <- registry.subscribeOrg(connId)
          _ <- sendMessage(channel, ServerMessage.Subscribed(List.empty, 0))
        yield ()

      case Right(ClientMessage.Unsubscribe(vehicleIds)) =>
        for
          total <- registry.unsubscribeVehicles(connId, vehicleIds.map(VehicleId(_)).toSet)
          _ <- sendMessage(channel, ServerMessage.Unsubscribed(vehicleIds, total))
        yield ()

      case Right(ClientMessage.UnsubscribeAll()) =>
        for
          _ <- registry.unsubscribeAll(connId)
          _ <- sendMessage(channel, ServerMessage.Unsubscribed(List.empty, 0))
        yield ()

      case Right(ClientMessage.Ping()) =>
        sendMessage(channel, ServerMessage.Pong())

      case Left(err) =>
        ZIO.logWarning(s"WS $connId: невалидное сообщение: $err") *>
        sendMessage(channel, ServerMessage.Error("INVALID_MESSAGE", s"Ошибка парсинга: $err"))

  /** Отправляет ServerMessage в WebSocket канал с обработкой ошибок */
  private def sendMessage(channel: WebSocketChannel, msg: ServerMessage): UIO[Unit] =
    channel.send(ChannelEvent.Read(WebSocketFrame.text(msg.toJson)))
      .catchAll(e => ZIO.logWarning(s"Ошибка отправки WS сообщения: ${e.getMessage}"))
