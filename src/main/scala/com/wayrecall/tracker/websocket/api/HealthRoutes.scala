package com.wayrecall.tracker.websocket.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.websocket.service.ConnectionRegistry

// ============================================================
// HEALTH ROUTES — HTTP API ДЛЯ МОНИТОРИНГА
// ============================================================
// GET /health — статус сервиса, кол-во соединений и подписок.
// Используется K8s liveness/readiness probe и Prometheus.
// ============================================================

/** Ответ health check */
final case class HealthResponse(
    status: String,
    connections: Int,
    subscriptions: Int
) derives JsonEncoder

object HealthRoutes:

  def routes: Routes[ConnectionRegistry, Nothing] = Routes(
    Method.GET / "health" -> handler { (_: Request) =>
      for
        registry <- ZIO.service[ConnectionRegistry]
        connCount <- registry.connectionCount
        subCount  <- registry.subscriptionCount
      yield Response.json(
        HealthResponse("healthy", connCount, subCount).toJson
      )
    }
  )
