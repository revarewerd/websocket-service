package com.wayrecall.tracker.websocket.domain

// ============================================================
// ТИПИЗИРОВАННЫЕ ОШИБКИ — WEBSOCKET SERVICE
// ============================================================

sealed trait WsError extends Exception:
  def message: String
  override def getMessage: String = message

object WsError:
  /** Ошибка аутентификации — невалидный или отсутствующий токен */
  final case class AuthenticationFailed(reason: String) extends WsError:
    val message = s"Ошибка аутентификации: $reason"

  /** Нет прав на подписку к запрошенным ТС */
  final case class SubscriptionDenied(vehicleIds: List[Long], reason: String) extends WsError:
    val message = s"Подписка запрещена для vehicleIds=$vehicleIds: $reason"

  /** Превышен лимит частоты сообщений от клиента */
  final case class RateLimitExceeded(retryAfterMs: Long) extends WsError:
    val message = s"Превышен лимит сообщений, повторите через ${retryAfterMs}мс"

  /** Невалидное сообщение от клиента */
  final case class InvalidMessage(details: String) extends WsError:
    val message = s"Невалидное сообщение: $details"

  /** Соединение закрыто */
  final case class ConnectionClosed(reason: String) extends WsError:
    val message = s"Соединение закрыто: $reason"

  /** Ошибка Kafka */
  final case class KafkaError(cause: Throwable) extends WsError:
    val message = s"Ошибка Kafka: ${cause.getMessage}"

  /** Отсутствует organizationId */
  case object MissingOrganizationId extends WsError:
    val message = "Отсутствует organizationId в параметрах подключения"
