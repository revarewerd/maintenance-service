package com.wayrecall.tracker.maintenance.domain

import zio.*
import zio.test.*
import zio.test.Assertion.*
import zio.json.*
import java.time.{Instant, LocalDate}
import java.util.UUID

// ============================================================
// ТЕСТЫ ДОМЕННЫХ МОДЕЛЕЙ — Maintenance Service
// ============================================================

object DomainSpec extends ZIOSpecDefault:

  private val now = Instant.now()
  private val today = LocalDate.now()

  def spec = suite("Domain — Maintenance Service")(

    // ---- IntervalType ----
    suite("IntervalType")(
      test("все 4 значения") {
        val all = List(IntervalType.Mileage, IntervalType.EngineHours, IntervalType.Calendar, IntervalType.Combined)
        assertTrue(all.length == 4)
      },
      test("JSON roundtrip — Combined") {
        val it = IntervalType.Combined
        val json = it.toJson
        val decoded = json.fromJson[IntervalType]
        assertTrue(decoded == Right(IntervalType.Combined))
      }
    ),

    // ---- ServicePriority ----
    suite("ServicePriority")(
      test("все 4 значения") {
        val all = List(ServicePriority.Critical, ServicePriority.High, ServicePriority.Normal, ServicePriority.Low)
        assertTrue(all.length == 4)
      },
      test("JSON roundtrip — Critical") {
        val sp = ServicePriority.Critical
        val json = sp.toJson
        val decoded = json.fromJson[ServicePriority]
        assertTrue(decoded == Right(ServicePriority.Critical))
      }
    ),

    // ---- ScheduleStatus ----
    suite("ScheduleStatus")(
      test("все 4 значения") {
        val all = List(ScheduleStatus.Active, ScheduleStatus.Paused, ScheduleStatus.Overdue, ScheduleStatus.Completed)
        assertTrue(all.length == 4)
      },
      test("JSON roundtrip") {
        val ss = ScheduleStatus.Overdue
        val json = ss.toJson
        val decoded = json.fromJson[ScheduleStatus]
        assertTrue(decoded == Right(ScheduleStatus.Overdue))
      }
    ),

    // ---- ServiceType ----
    suite("ServiceType")(
      test("все 4 значения") {
        val all = List(ServiceType.Scheduled, ServiceType.Unscheduled, ServiceType.Emergency, ServiceType.Inspection)
        assertTrue(all.length == 4)
      }
    ),

    // ---- MaintenanceEvent ----
    suite("MaintenanceEvent")(
      test("ScheduleCreated — JSON roundtrip") {
        val event = MaintenanceEvent.ScheduleCreated(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Замена масла"
        )
        val json = event.toJson
        val decoded = json.fromJson[MaintenanceEvent]
        assertTrue(decoded.isRight)
      },
      test("ServiceDueReminder — содержит remainingKm и priority") {
        val event = MaintenanceEvent.ServiceDueReminder(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          remainingKm = Some(500L), remainingDays = Some(7),
          priority = ServicePriority.High
        )
        val json = event.toJson
        assertTrue(json.contains("500"))
      },
      test("ServiceOverdue — overdueKm/Days") {
        val event = MaintenanceEvent.ServiceOverdue(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          overdueKm = Some(-200L), overdueDays = Some(-3)
        )
        val json = event.toJson
        assertTrue(json.contains("-200"))
      },
      test("ServiceCompleted — содержит mileageKm") {
        val event = MaintenanceEvent.ServiceCompleted(
          UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
          mileageKm = 150000L, serviceDate = today
        )
        val json = event.toJson
        assertTrue(json.contains("150000"))
      }
    ),

    // ---- ReminderConfig ----
    suite("ReminderConfig")(
      test("все пороги заполнены") {
        val rc = ReminderConfig(Some(500L), Some(7), Some(50))
        assertTrue(
          rc.thresholdKm.contains(500L),
          rc.thresholdDays.contains(7),
          rc.thresholdHours.contains(50)
        )
      },
      test("только пробег") {
        val rc = ReminderConfig(Some(1000L), None, None)
        assertTrue(
          rc.thresholdKm.contains(1000L),
          rc.thresholdDays.isEmpty,
          rc.thresholdHours.isEmpty
        )
      },
      test("JSON roundtrip") {
        val rc = ReminderConfig(Some(500L), Some(7), None)
        val json = rc.toJson
        val decoded = json.fromJson[ReminderConfig]
        assertTrue(decoded == Right(rc))
      }
    ),

    // ---- NextServiceInfo ----
    suite("NextServiceInfo")(
      test("все поля заполнены") {
        val info = NextServiceInfo(Some(50000L), Some(1000), Some(today.plusDays(90)))
        assertTrue(
          info.nextMileageKm.contains(50000L),
          info.nextEngineHours.contains(1000),
          info.nextDate.isDefined
        )
      },
      test("только по пробегу") {
        val info = NextServiceInfo(Some(50000L), None, None)
        assertTrue(
          info.nextMileageKm.isDefined,
          info.nextEngineHours.isEmpty,
          info.nextDate.isEmpty
        )
      }
    ),

    // ---- RemainingInfo ----
    suite("RemainingInfo")(
      test("положительные значения — ещё есть запас") {
        val info = RemainingInfo(Some(3000L), Some(200), Some(30))
        assertTrue(
          info.remainingKm.contains(3000L),
          info.remainingEngineHours.contains(200),
          info.remainingDays.contains(30)
        )
      },
      test("отрицательные — просрочено") {
        val info = RemainingInfo(Some(-500L), None, Some(-5))
        assertTrue(
          info.remainingKm.contains(-500L),
          info.remainingDays.contains(-5)
        )
      }
    ),

    // ---- ThresholdReached ----
    suite("ThresholdReached")(
      test("порог пробега достигнут") {
        val tr = ThresholdReached(UUID.randomUUID(), "km", "500", "350")
        assertTrue(
          tr.thresholdType == "km",
          tr.thresholdValue == "500",
          tr.currentValue == "350"
        )
      }
    ),

    // ---- MaintenanceTemplate ----
    suite("MaintenanceTemplate")(
      test("JSON roundtrip с items и reminders") {
        val tmpl = MaintenanceTemplate(
          id = UUID.randomUUID(), companyId = UUID.randomUUID(),
          name = "Замена масла и фильтров", description = Some("Каждые 15000 км"),
          vehicleType = Some("Грузовой"),
          intervalType = IntervalType.Combined,
          intervalMileageKm = Some(15000L),
          intervalEngineHours = Some(500),
          intervalDays = Some(180),
          priority = ServicePriority.Normal,
          estimatedDurationMinutes = Some(120),
          estimatedCostRub = Some(BigDecimal(5000)),
          items = List(MaintenanceItem(
            UUID.randomUUID(), UUID.randomUUID(),
            "Замена масла", Some("Моторное масло 5W-40"),
            Some("OIL-5W40-5L"), Some(BigDecimal(2500)), 1
          )),
          reminders = List(ReminderConfig(Some(500L), Some(7), None)),
          isActive = true, createdAt = now, updatedAt = now
        )
        val json = tmpl.toJson
        val decoded = json.fromJson[MaintenanceTemplate]
        assertTrue(decoded.isRight)
      }
    )
  )
