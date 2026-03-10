package com.wayrecall.tracker.maintenance.cache

import com.wayrecall.tracker.maintenance.domain.*
import zio.*
import zio.test.*
import zio.json.*
import java.util.UUID
import java.time.{Instant, LocalDate}

// ============================================================
// Тесты MaintenanceCache — in-memory кэширование через Ref
// Покрытие: пробег, расписания, флаги напоминаний, блокировки
// ============================================================

object MaintenanceCacheSpec extends ZIOSpecDefault:

  def spec = suite("MaintenanceCache")(
    mileageSuite,
    schedulesSuite,
    remindersSuite,
    locksSuite
  ) @@ TestAspect.timeout(60.seconds)

  val mileageSuite = suite("mileage")(
    test("getMileage — пустой кэш возвращает None") {
      for {
        cache  <- ZIO.service[MaintenanceCache]
        result <- cache.getMileage(UUID.randomUUID())
      } yield assertTrue(result.isEmpty)
    }.provide(MaintenanceCacheLive.live),

    test("setMileage + getMileage") {
      val vid = UUID.randomUUID()
      for {
        cache  <- ZIO.service[MaintenanceCache]
        _      <- cache.setMileage(vid, 150000L)
        result <- cache.getMileage(vid)
      } yield assertTrue(result.contains(150000L))
    }.provide(MaintenanceCacheLive.live),

    test("перезапись пробега") {
      val vid = UUID.randomUUID()
      for {
        cache  <- ZIO.service[MaintenanceCache]
        _      <- cache.setMileage(vid, 100000L)
        _      <- cache.setMileage(vid, 120000L)
        result <- cache.getMileage(vid)
      } yield assertTrue(result.contains(120000L))
    }.provide(MaintenanceCacheLive.live)
  )

  val schedulesSuite = suite("schedules")(
    test("getSchedules — пустой кэш") {
      for {
        cache  <- ZIO.service[MaintenanceCache]
        result <- cache.getSchedules(UUID.randomUUID())
      } yield assertTrue(result.isEmpty)
    }.provide(MaintenanceCacheLive.live),

    test("setSchedules + getSchedules") {
      val vid = UUID.randomUUID()
      val schedules = List(CachedSchedule(
        id = UUID.randomUUID(),
        templateName = "Тест",
        intervalType = IntervalType.Mileage,
        nextServiceMileageKm = Some(50000L),
        nextServiceDate = None,
        status = ScheduleStatus.Active,
        priority = ServicePriority.Normal
      ))
      for {
        cache  <- ZIO.service[MaintenanceCache]
        _      <- cache.setSchedules(vid, schedules)
        result <- cache.getSchedules(vid)
      } yield assertTrue(result.isDefined, result.get.length == 1)
    }.provide(MaintenanceCacheLive.live),

    test("invalidateSchedules — удаляет кэш") {
      val vid = UUID.randomUUID()
      val schedules = List(CachedSchedule(
        id = UUID.randomUUID(),
        templateName = "Тест",
        intervalType = IntervalType.Mileage,
        nextServiceMileageKm = None,
        nextServiceDate = None,
        status = ScheduleStatus.Active,
        priority = ServicePriority.Normal
      ))
      for {
        cache  <- ZIO.service[MaintenanceCache]
        _      <- cache.setSchedules(vid, schedules)
        _      <- cache.invalidateSchedules(vid)
        result <- cache.getSchedules(vid)
      } yield assertTrue(result.isEmpty)
    }.provide(MaintenanceCacheLive.live)
  )

  val remindersSuite = suite("reminders")(
    test("isReminderSent — не отправлено по умолчанию") {
      for {
        cache <- ZIO.service[MaintenanceCache]
        sent  <- cache.isReminderSent(UUID.randomUUID(), "km", "500")
      } yield assertTrue(!sent)
    }.provide(MaintenanceCacheLive.live),

    test("markReminderSent + isReminderSent") {
      val schedId = UUID.randomUUID()
      for {
        cache <- ZIO.service[MaintenanceCache]
        _     <- cache.markReminderSent(schedId, "km", "500")
        sent  <- cache.isReminderSent(schedId, "km", "500")
      } yield assertTrue(sent)
    }.provide(MaintenanceCacheLive.live),

    test("разные типы напоминаний — независимы") {
      val schedId = UUID.randomUUID()
      for {
        cache   <- ZIO.service[MaintenanceCache]
        _       <- cache.markReminderSent(schedId, "km", "500")
        kmSent  <- cache.isReminderSent(schedId, "km", "500")
        daySent <- cache.isReminderSent(schedId, "days", "7")
      } yield assertTrue(kmSent, !daySent)
    }.provide(MaintenanceCacheLive.live),

    test("разные расписания — независимы") {
      val s1 = UUID.randomUUID()
      val s2 = UUID.randomUUID()
      for {
        cache <- ZIO.service[MaintenanceCache]
        _     <- cache.markReminderSent(s1, "km", "500")
        sent1 <- cache.isReminderSent(s1, "km", "500")
        sent2 <- cache.isReminderSent(s2, "km", "500")
      } yield assertTrue(sent1, !sent2)
    }.provide(MaintenanceCacheLive.live)
  )

  val locksSuite = suite("locks")(
    test("acquireMileageLock — первый раз успешно") {
      val vid = UUID.randomUUID()
      for {
        cache    <- ZIO.service[MaintenanceCache]
        acquired <- cache.acquireMileageLock(vid)
      } yield assertTrue(acquired)
    }.provide(MaintenanceCacheLive.live),

    test("acquireMileageLock — повторный вызов → false") {
      val vid = UUID.randomUUID()
      for {
        cache <- ZIO.service[MaintenanceCache]
        _     <- cache.acquireMileageLock(vid)
        again <- cache.acquireMileageLock(vid)
      } yield assertTrue(!again)
    }.provide(MaintenanceCacheLive.live),

    test("releaseMileageLock — освобождение и повторный захват") {
      val vid = UUID.randomUUID()
      for {
        cache <- ZIO.service[MaintenanceCache]
        _     <- cache.acquireMileageLock(vid)
        _     <- cache.releaseMileageLock(vid)
        again <- cache.acquireMileageLock(vid)
      } yield assertTrue(again)
    }.provide(MaintenanceCacheLive.live),

    test("разные vehicleId — независимые блокировки") {
      val v1 = UUID.randomUUID()
      val v2 = UUID.randomUUID()
      for {
        cache <- ZIO.service[MaintenanceCache]
        _     <- cache.acquireMileageLock(v1)
        ok    <- cache.acquireMileageLock(v2)
      } yield assertTrue(ok)
    }.provide(MaintenanceCacheLive.live)
  )
