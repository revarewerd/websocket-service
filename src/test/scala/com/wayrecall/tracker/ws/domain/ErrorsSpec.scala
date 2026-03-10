package com.wayrecall.tracker.websocket.domain

import com.wayrecall.tracker.websocket.domain.*
import zio.*
import zio.test.*
import java.util.UUID

// ============================================================
// Тесты WsError — все типы ошибок WebSocket сервиса
// ============================================================

object ErrorsSpec extends ZIOSpecDefault:

  def spec = suite("WsError")(
    test("AuthenticationFailed — сообщение об ошибке") {
      val err = WsError.AuthenticationFailed("invalid token")
      assertTrue(
        err.message.contains("invalid token"),
        err.isInstanceOf[Throwable]
      )
    },

    test("SubscriptionDenied — vehicleIds в сообщении") {
      val ids = List(1L, 2L, 3L)
      val err = WsError.SubscriptionDenied(ids, "no access")
      assertTrue(
        err.message.nonEmpty,
        err.isInstanceOf[WsError]
      )
    },

    test("RateLimitExceeded — retryAfterMs") {
      val err = WsError.RateLimitExceeded(5000L)
      assertTrue(err.retryAfterMs == 5000L)
    },

    test("InvalidMessage — details") {
      val err = WsError.InvalidMessage("unknown command")
      assertTrue(err.message.contains("unknown"))
    },

    test("ConnectionClosed — reason") {
      val err = WsError.ConnectionClosed("timeout")
      assertTrue(err.message.nonEmpty)
    },

    test("KafkaError — cause") {
      val cause = new RuntimeException("connection refused")
      val err = WsError.KafkaError(cause)
      assertTrue(err.isInstanceOf[WsError])
    },

    test("MissingOrganizationId") {
      val err = WsError.MissingOrganizationId
      assertTrue(err.isInstanceOf[WsError])
    },

    test("все ошибки наследуют Throwable (для ZIO)") {
      val errors: List[WsError] = List(
        WsError.AuthenticationFailed("test"),
        WsError.SubscriptionDenied(Nil, "test"),
        WsError.RateLimitExceeded(1000),
        WsError.InvalidMessage("test"),
        WsError.ConnectionClosed("test"),
        WsError.KafkaError(new Exception("test")),
        WsError.MissingOrganizationId
      )
      assertTrue(errors.forall(_.isInstanceOf[Throwable]))
    }
  ) @@ TestAspect.timeout(60.seconds)
