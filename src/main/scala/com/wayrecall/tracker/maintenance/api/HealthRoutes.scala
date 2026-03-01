package com.wayrecall.tracker.maintenance.api

import zio.*
import zio.http.*

// ============================================================
// HEALTH ROUTES — Maintenance Service
// GET /health — liveness probe
// GET /ready  — readiness probe
// ============================================================

object HealthRoutes:

  val routes: Routes[Any, Nothing] = Routes(
    Method.GET / "health" -> handler {
      Response.json("""{"status":"UP","service":"maintenance-service"}""")
    },
    Method.GET / "ready" -> handler {
      Response.json("""{"status":"READY","service":"maintenance-service"}""")
    }
  )
