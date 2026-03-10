package com.wayrecall.tracker.maintenance.service

import zio.*
import zio.test.*
import zio.test.Assertion.*
import com.wayrecall.tracker.maintenance.domain.*
import java.time.{Instant, LocalDate}
import java.util.UUID

// ============================================================
// ТЕСТЫ INTERVAL CALCULATOR — расчёт интервалов ТО
// ============================================================

object IntervalCalculatorSpec extends ZIOSpecDefault:

  private val testLayer = IntervalCalculatorLive.live

  private val now = Instant.now()
  private val today = LocalDate.now()

  // Вспомогательный шаблон
  private def makeTemplate(
    mileageKm: Option[Long] = None,
    engineHours: Option[Int] = None,
    days: Option[Int] = None,
    intervalType: IntervalType = IntervalType.Combined
  ): MaintenanceTemplate =
    MaintenanceTemplate(
      id = UUID.randomUUID(), companyId = UUID.randomUUID(),
      name = "Тест", description = None, vehicleType = None,
      intervalType = intervalType,
      intervalMileageKm = mileageKm,
      intervalEngineHours = engineHours,
      intervalDays = days,
      priority = ServicePriority.Normal,
      estimatedDurationMinutes = None, estimatedCostRub = None,
      items = Nil, reminders = Nil,
      isActive = true, createdAt = now, updatedAt = now
    )

  // Вспомогательное расписание
  private def makeSchedule(
    currentKm: Long = 50000L,
    currentHours: Int = 1000,
    nextKm: Option[Long] = None,
    nextHours: Option[Int] = None,
    nextDate: Option[LocalDate] = None,
    reminders: List[ReminderConfig] = Nil
  ): MaintenanceSchedule =
    val remainKm = nextKm.map(_ - currentKm)
    val remainHours = nextHours.map(_ - currentHours)
    val remainDays = nextDate.map(d => java.time.temporal.ChronoUnit.DAYS.between(today, d).toInt)
    MaintenanceSchedule(
      id = UUID.randomUUID(), companyId = UUID.randomUUID(),
      vehicleId = UUID.randomUUID(), templateId = UUID.randomUUID(),
      templateName = "Тест", intervalType = IntervalType.Combined,
      intervalMileageKm = Some(15000L), intervalEngineHours = Some(500),
      intervalDays = Some(180),
      priority = ServicePriority.Normal, status = ScheduleStatus.Active,
      currentMileageKm = currentKm, currentEngineHours = currentHours,
      lastServiceDate = None, lastServiceMileageKm = None, lastServiceEngineHours = None,
      nextServiceMileageKm = nextKm, nextServiceEngineHours = nextHours,
      nextServiceDate = nextDate,
      remainingKm = remainKm, remainingEngineHours = remainHours, remainingDays = remainDays,
      reminderFirstSent = false, reminderSecondSent = false,
      reminderThirdSent = false, reminderOverdueSent = false,
      createdAt = now, updatedAt = now
    )

  def spec = suite("IntervalCalculator")(

    // ---- calculateNextService ----
    suite("calculateNextService")(
      test("только пробег — следующий = текущий + интервал") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate(mileageKm = Some(15000L))
          result <- calc.calculateNextService(tmpl, 50000L, 0, None, None, None)
        } yield assertTrue(
          result.nextMileageKm.contains(65000L), // 50000 + 15000
          result.nextEngineHours.isEmpty,
          result.nextDate.isEmpty
        )
      }.provide(testLayer),

      test("только моточасы — следующий = текущие + интервал") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate(engineHours = Some(500))
          result <- calc.calculateNextService(tmpl, 0L, 1000, None, None, None)
        } yield assertTrue(result.nextEngineHours.contains(1500)) // 1000 + 500
      }.provide(testLayer),

      test("только календарь — следующий = сегодня + интервал") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate(days = Some(180))
          result <- calc.calculateNextService(tmpl, 0L, 0, None, None, None)
        } yield assertTrue(
          result.nextDate.isDefined,
          result.nextDate.get.isAfter(today)
        )
      }.provide(testLayer),

      test("комбинированный — все 3 поля заполнены") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate(mileageKm = Some(15000L), engineHours = Some(500), days = Some(180))
          result <- calc.calculateNextService(tmpl, 50000L, 1000, None, None, None)
        } yield assertTrue(
          result.nextMileageKm.contains(65000L),
          result.nextEngineHours.contains(1500),
          result.nextDate.isDefined
        )
      }.provide(testLayer),

      test("с предыдущим ТО — базируется на lastService") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate(mileageKm = Some(15000L))
          result <- calc.calculateNextService(tmpl, 52000L, 0, None, Some(45000L), None)
        } yield assertTrue(
          result.nextMileageKm.contains(60000L) // 45000 + 15000
        )
      }.provide(testLayer),

      test("пустой шаблон — все None") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          tmpl = makeTemplate()
          result <- calc.calculateNextService(tmpl, 50000L, 1000, None, None, None)
        } yield assertTrue(
          result.nextMileageKm.isEmpty,
          result.nextEngineHours.isEmpty,
          result.nextDate.isEmpty
        )
      }.provide(testLayer)
    ),

    // ---- calculateRemaining ----
    suite("calculateRemaining")(
      test("ещё есть запас — положительные значения") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(
            currentKm = 47000L,
            nextKm = Some(50000L),
            nextHours = Some(1500),
            currentHours = 1200
          )
          result <- calc.calculateRemaining(schedule)
        } yield assertTrue(
          result.remainingKm.contains(3000L),    // 50000 - 47000
          result.remainingEngineHours.contains(300) // 1500 - 1200
        )
      }.provide(testLayer),

      test("просрочено — отрицательные значения") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(
            currentKm = 52000L,
            nextKm = Some(50000L)
          )
          result <- calc.calculateRemaining(schedule)
        } yield assertTrue(
          result.remainingKm.contains(-2000L) // 50000 - 52000
        )
      }.provide(testLayer),

      test("нет следующего порога — все None") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule()
          result <- calc.calculateRemaining(schedule)
        } yield assertTrue(
          result.remainingKm.isEmpty,
          result.remainingEngineHours.isEmpty
        )
      }.provide(testLayer)
    ),

    // ---- checkThresholds ----
    suite("checkThresholds")(
      test("порог пробега достигнут") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(
            currentKm = 49500L,
            nextKm = Some(50000L)
          )
          thresholds = List(ReminderConfig(Some(1000L), None, None)) // За 1000 км
          reached <- calc.checkThresholds(schedule, thresholds)
        } yield assertTrue(
          reached.length == 1,
          reached.head.thresholdType == "km"
        )
      }.provide(testLayer),

      test("порог не достигнут — далеко от ТО") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(
            currentKm = 40000L,
            nextKm = Some(50000L)
          )
          thresholds = List(ReminderConfig(Some(500L), None, None)) // За 500 км
          reached <- calc.checkThresholds(schedule, thresholds)
        } yield assertTrue(reached.isEmpty) // 10000 км остаток > 500
      }.provide(testLayer),

      test("несколько порогов — все достигнутые возвращаются") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(
            currentKm = 49800L,
            nextKm = Some(50000L),
            nextDate = Some(today.plusDays(3))
          )
          thresholds = List(
            ReminderConfig(Some(500L), Some(7), None),
            ReminderConfig(Some(100L), Some(3), None)
          )
          reached <- calc.checkThresholds(schedule, thresholds)
        } yield assertTrue(reached.nonEmpty) // Несколько порогов достигнуты
      }.provide(testLayer),

      test("пустой список порогов — пустой результат") {
        for {
          calc <- ZIO.service[IntervalCalculator]
          schedule = makeSchedule(currentKm = 49999L, nextKm = Some(50000L))
          reached <- calc.checkThresholds(schedule, Nil)
        } yield assertTrue(reached.isEmpty)
      }.provide(testLayer)
    )
  )
