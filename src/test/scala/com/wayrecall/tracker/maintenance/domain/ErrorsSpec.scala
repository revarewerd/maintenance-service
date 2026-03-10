package com.wayrecall.tracker.maintenance.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.util.UUID

// ============================================================
// ТЕСТЫ ОШИБОК — Maintenance Service
// ============================================================

object ErrorsSpec extends ZIOSpecDefault:

  def spec = suite("MaintenanceError")(

    suite("Все 13 подтипов ошибки")(
      test("TemplateNotFound — содержит UUID") {
        val id = UUID.randomUUID()
        val err = MaintenanceError.TemplateNotFound(id)
        assertTrue(err.message.contains(id.toString))
      },
      test("TemplateAlreadyExists — содержит имя и companyId") {
        val cid = UUID.randomUUID()
        val err = MaintenanceError.TemplateAlreadyExists("Замена масла", cid)
        assertTrue(
          err.message.contains("Замена масла"),
          err.message.contains(cid.toString)
        )
      },
      test("ScheduleNotFound — содержит UUID") {
        val id = UUID.randomUUID()
        val err = MaintenanceError.ScheduleNotFound(id)
        assertTrue(err.message.contains(id.toString))
      },
      test("ScheduleAlreadyExists — содержит vehicleId и templateId") {
        val vid = UUID.randomUUID()
        val tid = UUID.randomUUID()
        val err = MaintenanceError.ScheduleAlreadyExists(vid, tid)
        assertTrue(
          err.message.contains(vid.toString),
          err.message.contains(tid.toString)
        )
      },
      test("VehicleNotFound — содержит UUID") {
        val id = UUID.randomUUID()
        val err = MaintenanceError.VehicleNotFound(id)
        assertTrue(err.message.contains(id.toString))
      },
      test("InvalidInterval — содержит причину") {
        val err = MaintenanceError.InvalidInterval("km must be > 0")
        assertTrue(err.message.contains("km must be > 0"))
      },
      test("InvalidRequest — содержит причину") {
        val err = MaintenanceError.InvalidRequest("empty name")
        assertTrue(err.message.contains("empty name"))
      },
      test("ReminderAlreadySent — содержит scheduleId и threshold") {
        val sid = UUID.randomUUID()
        val err = MaintenanceError.ReminderAlreadySent(sid, "km_500")
        assertTrue(
          err.message.contains(sid.toString),
          err.message.contains("km_500")
        )
      },
      test("DatabaseError — оборачивает Throwable") {
        val cause = new RuntimeException("Connection timeout")
        val err = MaintenanceError.DatabaseError(cause)
        assertTrue(err.message.contains("Connection timeout"))
      },
      test("CacheError — оборачивает Throwable") {
        val cause = new RuntimeException("Redis down")
        val err = MaintenanceError.CacheError(cause)
        assertTrue(err.message.contains("Redis down"))
      },
      test("KafkaError — оборачивает Throwable") {
        val cause = new RuntimeException("Broker unreachable")
        val err = MaintenanceError.KafkaError(cause)
        assertTrue(err.message.contains("Broker unreachable"))
      },
      test("LockAcquireError — содержит ключ") {
        val err = MaintenanceError.LockAcquireError("mileage:vehicle-42")
        assertTrue(err.message.contains("mileage:vehicle-42"))
      },
      test("ServiceRecordNotFound — содержит UUID") {
        val id = UUID.randomUUID()
        val err = MaintenanceError.ServiceRecordNotFound(id)
        assertTrue(err.message.contains(id.toString))
      }
    ),

    suite("Exhaustive pattern matching")(
      test("все 13 подтипов обрабатываются") {
        val id = UUID.randomUUID()
        val errors: List[MaintenanceError] = List(
          MaintenanceError.TemplateNotFound(id),
          MaintenanceError.TemplateAlreadyExists("x", id),
          MaintenanceError.ScheduleNotFound(id),
          MaintenanceError.ScheduleAlreadyExists(id, id),
          MaintenanceError.VehicleNotFound(id),
          MaintenanceError.InvalidInterval("x"),
          MaintenanceError.InvalidRequest("x"),
          MaintenanceError.ReminderAlreadySent(id, "x"),
          MaintenanceError.DatabaseError(new RuntimeException("x")),
          MaintenanceError.CacheError(new RuntimeException("x")),
          MaintenanceError.KafkaError(new RuntimeException("x")),
          MaintenanceError.LockAcquireError("x"),
          MaintenanceError.ServiceRecordNotFound(id)
        )
        assertTrue(
          errors.length == 13,
          errors.forall(_.message.nonEmpty)
        )
      }
    ),

    suite("MaintenanceError — НЕ extends Throwable")(
      test("ошибка не является Throwable") {
        val err: MaintenanceError = MaintenanceError.InvalidRequest("test")
        assertTrue(!err.isInstanceOf[Throwable])
      }
    )
  )
