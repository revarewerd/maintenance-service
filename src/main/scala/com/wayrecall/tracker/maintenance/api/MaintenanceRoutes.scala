package com.wayrecall.tracker.maintenance.api

import zio.*
import zio.http.*
import zio.json.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.service.MaintenanceService
import java.util.UUID
import java.time.LocalDate

// ============================================================
// MAINTENANCE ROUTES — REST API
//
// Шаблоны ТО:
//   GET    /api/v1/templates                    — список шаблонов компании
//   POST   /api/v1/templates                    — создать шаблон
//   GET    /api/v1/templates/:id                — получить шаблон
//   PUT    /api/v1/templates/:id                — обновить шаблон
//   DELETE /api/v1/templates/:id                — удалить шаблон
//
// Расписания:
//   GET    /api/v1/vehicles/:id/schedules       — расписания для ТС
//   POST   /api/v1/schedules                    — создать расписание
//   GET    /api/v1/vehicles/:id/overview        — обзор ТО по ТС
//   PUT    /api/v1/schedules/:id/pause          — приостановить
//   PUT    /api/v1/schedules/:id/resume         — возобновить
//
// Записи о ТО:
//   POST   /api/v1/services                     — зарегистрировать ТО
//   GET    /api/v1/vehicles/:id/services        — история ТО
//   GET    /api/v1/services/:id                 — получить запись
//
// Обзоры:
//   GET    /api/v1/company/overview              — обзор по компании
// ============================================================

object MaintenanceRoutes:

  type Env = MaintenanceService

  val routes: Routes[Env, Nothing] = Routes(
    // ---- Шаблоны ТО ----

    // GET /api/v1/templates?vehicleType=truck
    Method.GET / "api" / "v1" / "templates" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      val vehicleType = req.url.queryParams.get("vehicleType").flatMap(_.headOption)
      MaintenanceService.listTemplates(companyId, vehicleType)
        .map(templates => Response.json(templates.toJson))
        .catchAll(handleError)
    },

    // POST /api/v1/templates
    Method.POST / "api" / "v1" / "templates" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      (for {
        body     <- req.body.asString
        request  <- ZIO.fromEither(body.fromJson[CreateTemplateRequest])
                      .mapError(err => new RuntimeException(s"Невалидный JSON: $err"))
        template <- MaintenanceService.createTemplate(companyId, request)
      } yield Response.json(template.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // GET /api/v1/templates/:id
    Method.GET / "api" / "v1" / "templates" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        uuid     <- parseUUID(id)
        template <- MaintenanceService.getTemplate(uuid)
        response <- template match
                      case Some(t) => ZIO.succeed(Response.json(t.toJson))
                      case None    => ZIO.succeed(Response.json(s"""{"error":"Шаблон не найден: $id"}""").status(Status.NotFound))
      } yield response).catchAll(handleError)
    },

    // PUT /api/v1/templates/:id
    Method.PUT / "api" / "v1" / "templates" / string("id") -> handler { (id: String, req: Request) =>
      (for {
        uuid    <- parseUUID(id)
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[CreateTemplateRequest])
                    .mapError(err => new RuntimeException(s"Невалидный JSON: $err"))
        _       <- MaintenanceService.updateTemplate(uuid, request)
      } yield Response.json("""{"status":"updated"}"""))
        .catchAll(handleError)
    },

    // DELETE /api/v1/templates/:id
    Method.DELETE / "api" / "v1" / "templates" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        uuid <- parseUUID(id)
        _    <- MaintenanceService.deleteTemplate(uuid)
      } yield Response.json("""{"status":"deleted"}"""))
        .catchAll(handleError)
    },

    // ---- Расписания ----

    // GET /api/v1/vehicles/:id/schedules
    Method.GET / "api" / "v1" / "vehicles" / string("vehicleId") / "schedules" -> handler { (vehicleId: String, _: Request) =>
      (for {
        uuid      <- parseUUID(vehicleId)
        schedules <- MaintenanceService.getVehicleSchedules(uuid)
      } yield Response.json(schedules.toJson))
        .catchAll(handleError)
    },

    // POST /api/v1/schedules
    Method.POST / "api" / "v1" / "schedules" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      (for {
        body     <- req.body.asString
        request  <- ZIO.fromEither(body.fromJson[CreateScheduleRequest])
                      .mapError(err => new RuntimeException(s"Невалидный JSON: $err"))
        schedule <- MaintenanceService.createSchedule(companyId, request)
      } yield Response.json(schedule.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // GET /api/v1/vehicles/:id/overview
    Method.GET / "api" / "v1" / "vehicles" / string("vehicleId") / "overview" -> handler { (vehicleId: String, _: Request) =>
      (for {
        uuid     <- parseUUID(vehicleId)
        overview <- MaintenanceService.getVehicleOverview(uuid)
      } yield Response.json(overview.toJson))
        .catchAll(handleError)
    },

    // PUT /api/v1/schedules/:id/pause
    Method.PUT / "api" / "v1" / "schedules" / string("id") / "pause" -> handler { (id: String, _: Request) =>
      (for {
        uuid <- parseUUID(id)
        _    <- MaintenanceService.pauseSchedule(uuid)
      } yield Response.json("""{"status":"paused"}"""))
        .catchAll(handleError)
    },

    // PUT /api/v1/schedules/:id/resume
    Method.PUT / "api" / "v1" / "schedules" / string("id") / "resume" -> handler { (id: String, _: Request) =>
      (for {
        uuid <- parseUUID(id)
        _    <- MaintenanceService.resumeSchedule(uuid)
      } yield Response.json("""{"status":"resumed"}"""))
        .catchAll(handleError)
    },

    // ---- Записи о ТО ----

    // POST /api/v1/services
    Method.POST / "api" / "v1" / "services" -> handler { (req: Request) =>
      val userId = extractUserId(req)
      (for {
        body    <- req.body.asString
        request <- ZIO.fromEither(body.fromJson[RecordServiceRequest])
                    .mapError(err => new RuntimeException(s"Невалидный JSON: $err"))
        record  <- MaintenanceService.recordService(userId, request)
      } yield Response.json(record.toJson).status(Status.Created))
        .catchAll(handleError)
    },

    // GET /api/v1/vehicles/:id/services?limit=20&offset=0
    Method.GET / "api" / "v1" / "vehicles" / string("vehicleId") / "services" -> handler { (vehicleId: String, req: Request) =>
      val limit  = req.url.queryParams.get("limit").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(20)
      val offset = req.url.queryParams.get("offset").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(0)
      (for {
        uuid    <- parseUUID(vehicleId)
        history <- MaintenanceService.getServiceHistory(uuid, limit, offset)
      } yield Response.json(history.toJson))
        .catchAll(handleError)
    },

    // GET /api/v1/services/:id
    Method.GET / "api" / "v1" / "services" / string("id") -> handler { (id: String, _: Request) =>
      (for {
        uuid   <- parseUUID(id)
        record <- MaintenanceService.getServiceRecord(uuid)
        response <- record match
                      case Some(r) => ZIO.succeed(Response.json(r.toJson))
                      case None    => ZIO.succeed(Response.json(s"""{"error":"Запись ТО не найдена: $id"}""").status(Status.NotFound))
      } yield response).catchAll(handleError)
    },

    // ---- Обзоры ----

    // GET /api/v1/company/overview
    Method.GET / "api" / "v1" / "company" / "overview" -> handler { (req: Request) =>
      val companyId = extractCompanyId(req)
      MaintenanceService.getCompanyOverview(companyId)
        .map(overview => Response.json(overview.toJson))
        .catchAll(handleError)
    }
  )

  // ---- Вспомогательные методы ----

  /** Извлечение companyId из заголовка (будет из JWT через API Gateway) */
  private def extractCompanyId(req: Request): UUID =
    req.header("X-Company-Id")
      .flatMap(h => scala.util.Try(UUID.fromString(h.renderedValue)).toOption)
      .getOrElse(UUID.nameUUIDFromBytes("default-company".getBytes))

  /** Извлечение userId из заголовка */
  private def extractUserId(req: Request): UUID =
    req.header("X-User-Id")
      .flatMap(h => scala.util.Try(UUID.fromString(h.renderedValue)).toOption)
      .getOrElse(UUID.nameUUIDFromBytes("default-user".getBytes))

  /** Парсинг UUID из строки */
  private def parseUUID(id: String): Task[UUID] =
    ZIO.attempt(UUID.fromString(id))
      .mapError(_ => new RuntimeException(s"Невалидный UUID: $id"))

  /** Обработчик ошибок → HTTP ответ */
  private def handleError(err: Throwable): UIO[Response] =
    ZIO.logError(s"API ошибка: ${err.getMessage}").as(
      err.getMessage match
        case msg if msg.contains("не найден") || msg.contains("not found") =>
          Response.json(s"""{"error":"$msg"}""").status(Status.NotFound)
        case msg if msg.contains("Невалидный") || msg.contains("invalid") =>
          Response.json(s"""{"error":"$msg"}""").status(Status.BadRequest)
        case msg =>
          Response.json(s"""{"error":"Внутренняя ошибка сервера"}""").status(Status.InternalServerError)
    )

  // Accessor метод для ZIO.serviceWithZIO
  private object MaintenanceService:
    def listTemplates(companyId: UUID, vehicleType: Option[String]) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.listTemplates(companyId, vehicleType))
    def createTemplate(companyId: UUID, request: CreateTemplateRequest) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.createTemplate(companyId, request))
    def getTemplate(id: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getTemplate(id))
    def updateTemplate(id: UUID, request: CreateTemplateRequest) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.updateTemplate(id, request))
    def deleteTemplate(id: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.deleteTemplate(id))
    def getVehicleSchedules(vehicleId: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getVehicleSchedules(vehicleId))
    def createSchedule(companyId: UUID, request: CreateScheduleRequest) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.createSchedule(companyId, request))
    def getVehicleOverview(vehicleId: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getVehicleOverview(vehicleId))
    def pauseSchedule(scheduleId: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.pauseSchedule(scheduleId))
    def resumeSchedule(scheduleId: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.resumeSchedule(scheduleId))
    def recordService(userId: UUID, request: RecordServiceRequest) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.recordService(userId, request))
    def getServiceHistory(vehicleId: UUID, limit: Int, offset: Int) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getServiceHistory(vehicleId, limit, offset))
    def getServiceRecord(id: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getServiceRecord(id))
    def getCompanyOverview(companyId: UUID) =
      ZIO.serviceWithZIO[com.wayrecall.tracker.maintenance.service.MaintenanceService](_.getCompanyOverview(companyId))
