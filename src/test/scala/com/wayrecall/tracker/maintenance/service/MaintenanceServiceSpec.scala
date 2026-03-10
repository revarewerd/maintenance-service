package com.wayrecall.tracker.maintenance.service

import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.repository.*
import zio.*
import zio.test.*
import java.util.UUID
import java.time.{Instant, LocalDate}

// ============================================================
// Тесты MileageTracker, ReminderEngine, MaintenancePlanner
// In-memory хранилища для репозиториев
// ============================================================

object MaintenanceServiceSpec extends ZIOSpecDefault:

  // --- InMemory OdometerRepository ---
  final case class InMemoryOdometerRepo(
    readings: Ref[List[OdometerReading]],
    dailyMileageStore: Ref[List[DailyMileage]],
    engineHoursStore: Ref[List[EngineHoursReading]]
  ) extends OdometerRepository:

    override def saveReading(reading: OdometerReading): Task[Unit] =
      readings.update(_ :+ reading)

    override def getLatest(vehicleId: UUID): Task[Option[OdometerReading]] =
      readings.get.map(_.filter(_.vehicleId == vehicleId)
        .maxByOption(_.recordedAt.toEpochMilli))

    override def getReadings(vehicleId: UUID, from: Instant, to: Instant): Task[List[OdometerReading]] =
      readings.get.map(_.filter(r =>
        r.vehicleId == vehicleId &&
        !r.recordedAt.isBefore(from) &&
        !r.recordedAt.isAfter(to)))

    override def saveDailyMileage(dm: DailyMileage): Task[Unit] =
      dailyMileageStore.update(_ :+ dm)

    override def getDailyMileage(vehicleId: UUID, date: LocalDate): Task[Option[DailyMileage]] =
      dailyMileageStore.get.map(_.find(d => d.vehicleId == vehicleId && d.date == date))

    override def getDailyMileageRange(vehicleId: UUID, from: LocalDate, to: LocalDate): Task[List[DailyMileage]] =
      dailyMileageStore.get.map(_.filter(d =>
        d.vehicleId == vehicleId &&
        !d.date.isBefore(from) &&
        !d.date.isAfter(to)))

    override def saveEngineHours(reading: EngineHoursReading): Task[Unit] =
      engineHoursStore.update(_ :+ reading)

    override def getLatestEngineHours(vehicleId: UUID): Task[Option[EngineHoursReading]] =
      engineHoursStore.get.map(_.filter(_.vehicleId == vehicleId)
        .maxByOption(_.recordedAt.toEpochMilli))

  object InMemoryOdometerRepo:
    val live: ZLayer[Any, Nothing, OdometerRepository] =
      ZLayer {
        for {
          r <- Ref.make(List.empty[OdometerReading])
          d <- Ref.make(List.empty[DailyMileage])
          e <- Ref.make(List.empty[EngineHoursReading])
        } yield InMemoryOdometerRepo(r, d, e)
      }

  // --- InMemory TemplateRepository ---
  final case class InMemoryTemplateRepo(
    store: Ref[Map[UUID, MaintenanceTemplate]]
  ) extends TemplateRepository:

    override def create(template: MaintenanceTemplate): Task[UUID] =
      store.update(_ + (template.id -> template)).as(template.id)

    override def findById(id: UUID): Task[Option[MaintenanceTemplate]] =
      store.get.map(_.get(id))

    override def findByCompany(companyId: UUID, vehicleType: Option[String]): Task[List[MaintenanceTemplate]] =
      store.get.map(_.values.filter { t =>
        t.companyId == companyId && vehicleType.forall(vt => t.vehicleType.contains(vt))
      }.toList)

    override def update(template: MaintenanceTemplate): Task[Unit] =
      store.update(_ + (template.id -> template))

    override def delete(id: UUID): Task[Unit] =
      store.update(_ - id)

    override def existsByName(companyId: UUID, name: String): Task[Boolean] =
      store.get.map(_.values.exists(t => t.companyId == companyId && t.name == name))

  object InMemoryTemplateRepo:
    val live: ZLayer[Any, Nothing, TemplateRepository] =
      ZLayer(Ref.make(Map.empty[UUID, MaintenanceTemplate]).map(InMemoryTemplateRepo(_)))

  // --- InMemory ScheduleRepository ---
  final case class InMemoryScheduleRepo(
    store: Ref[Map[UUID, MaintenanceSchedule]]
  ) extends ScheduleRepository:

    override def create(schedule: MaintenanceSchedule): Task[UUID] =
      store.update(_ + (schedule.id -> schedule)).as(schedule.id)

    override def findById(id: UUID): Task[Option[MaintenanceSchedule]] =
      store.get.map(_.get(id))

    override def findByVehicle(vehicleId: UUID): Task[List[MaintenanceSchedule]] =
      store.get.map(_.values.filter(_.vehicleId == vehicleId).toList)

    override def findActiveByCompany(companyId: UUID): Task[List[MaintenanceSchedule]] =
      store.get.map(_.values.filter(s =>
        s.companyId == companyId && s.status == ScheduleStatus.Active).toList)

    override def findOverdue(): Task[List[MaintenanceSchedule]] =
      store.get.map(_.values.filter(_.status == ScheduleStatus.Overdue).toList)

    override def findApproachingThreshold(thresholdKm: Long, thresholdDays: Int): Task[List[MaintenanceSchedule]] =
      store.get.map(_.values.filter { s =>
        s.status == ScheduleStatus.Active && (
          s.remainingKm.exists(r => r > 0 && r <= thresholdKm) ||
          s.remainingDays.exists(r => r > 0 && r <= thresholdDays)
        )
      }.toList)

    override def updateMileage(vehicleId: UUID, mileageKm: Long, remainingKm: Option[Long]): Task[Unit] =
      store.update(m => m.map { case (k, v) =>
        if v.vehicleId == vehicleId then
          (k, v.copy(currentMileageKm = mileageKm, remainingKm = remainingKm))
        else (k, v)
      })

    override def updateEngineHours(vehicleId: UUID, hours: Int, remainingHours: Option[Int]): Task[Unit] =
      store.update(m => m.map { case (k, v) =>
        if v.vehicleId == vehicleId then
          (k, v.copy(currentEngineHours = hours, remainingEngineHours = remainingHours))
        else (k, v)
      })

    override def updateReminderFlag(scheduleId: UUID, flagName: String): Task[Unit] =
      store.update(m => m.updatedWith(scheduleId)(_.map { s =>
        flagName match
          case "reminderFirstSent"   => s.copy(reminderFirstSent = true)
          case "reminderSecondSent"  => s.copy(reminderSecondSent = true)
          case "reminderThirdSent"   => s.copy(reminderThirdSent = true)
          case "reminderOverdueSent" => s.copy(reminderOverdueSent = true)
          case _ => s
      }))

    override def updateStatus(scheduleId: UUID, status: ScheduleStatus): Task[Unit] =
      store.update(m => m.updatedWith(scheduleId)(_.map(_.copy(status = status))))

    override def updateAfterService(
      scheduleId: UUID,
      nextMileage: Option[Long],
      nextDate: Option[LocalDate],
      nextHours: Option[Int]
    ): Task[Unit] =
      store.update(m => m.updatedWith(scheduleId)(_.map(_.copy(
        nextServiceMileageKm = nextMileage,
        nextServiceDate = nextDate,
        nextServiceEngineHours = nextHours,
        reminderFirstSent = false,
        reminderSecondSent = false,
        reminderThirdSent = false,
        reminderOverdueSent = false,
        status = ScheduleStatus.Active
      ))))

    override def resetReminderFlags(scheduleId: UUID): Task[Unit] =
      store.update(m => m.updatedWith(scheduleId)(_.map(_.copy(
        reminderFirstSent = false,
        reminderSecondSent = false,
        reminderThirdSent = false,
        reminderOverdueSent = false
      ))))

  object InMemoryScheduleRepo:
    val live: ZLayer[Any, Nothing, ScheduleRepository] =
      ZLayer(Ref.make(Map.empty[UUID, MaintenanceSchedule]).map(InMemoryScheduleRepo(_)))

  // --- Тестовые данные ---
  private val companyId = UUID.randomUUID()
  private val vehicleId = UUID.randomUUID()
  private val now = Instant.now()
  private val today = LocalDate.now()

  // Вспомогательный метод для создания шаблона
  private def makeTemplate(
    id: UUID = UUID.randomUUID(),
    company: UUID = companyId,
    name: String = "Замена масла",
    description: Option[String] = Some("ТО двигателя"),
    intervalType: IntervalType = IntervalType.Mileage,
    mileageKm: Option[Long] = Some(15000L)
  ): MaintenanceTemplate =
    MaintenanceTemplate(
      id = id, companyId = company,
      name = name, description = description,
      vehicleType = None,
      intervalType = intervalType,
      intervalMileageKm = mileageKm,
      intervalEngineHours = None,
      intervalDays = None,
      priority = ServicePriority.Normal,
      estimatedDurationMinutes = None,
      estimatedCostRub = None,
      items = Nil,
      reminders = Nil,
      isActive = true,
      createdAt = now, updatedAt = now
    )

  // Вспомогательный метод для создания расписания
  private def makeSchedule(
    id: UUID = UUID.randomUUID(),
    vehicle: UUID = vehicleId,
    company: UUID = companyId,
    status: ScheduleStatus = ScheduleStatus.Active,
    currentMileageKm: Long = 100000L,
    nextMileageKm: Option[Long] = Some(115000L),
    nextDate: Option[LocalDate] = Some(LocalDate.now().plusDays(30))
  ): MaintenanceSchedule =
    MaintenanceSchedule(
      id = id, companyId = company,
      vehicleId = vehicle, templateId = UUID.randomUUID(),
      templateName = "Тест",
      intervalType = IntervalType.Combined,
      intervalMileageKm = Some(15000L),
      intervalEngineHours = Some(500),
      intervalDays = Some(180),
      priority = ServicePriority.Normal,
      status = status,
      currentMileageKm = currentMileageKm,
      currentEngineHours = 5000,
      lastServiceDate = None,
      lastServiceMileageKm = None,
      lastServiceEngineHours = None,
      nextServiceMileageKm = nextMileageKm,
      nextServiceEngineHours = Some(5500),
      nextServiceDate = nextDate,
      remainingKm = nextMileageKm.map(_ - currentMileageKm),
      remainingEngineHours = Some(500),
      remainingDays = nextDate.map(d => java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d).toInt),
      reminderFirstSent = false,
      reminderSecondSent = false,
      reminderThirdSent = false,
      reminderOverdueSent = false,
      createdAt = now, updatedAt = now
    )

  def spec = suite("MaintenanceService — unit тесты")(
    odometerSuite,
    templateSuite,
    scheduleSuite,
    domainSuite
  ) @@ TestAspect.timeout(60.seconds)

  val odometerSuite = suite("OdometerRepository")(
    test("saveReading + getLatest") {
      val vid = UUID.randomUUID()
      val r1 = OdometerReading(UUID.randomUUID(), vid, 100000L, "gps", now.minusSeconds(60), now)
      val r2 = OdometerReading(UUID.randomUUID(), vid, 100051L, "gps", now, now)
      for {
        repo   <- ZIO.service[OdometerRepository]
        _      <- repo.saveReading(r1) *> repo.saveReading(r2)
        latest <- repo.getLatest(vid)
      } yield assertTrue(
        latest.isDefined,
        latest.get.mileageKm == 100051L
      )
    }.provide(InMemoryOdometerRepo.live),

    test("getReadings — фильтр по диапазону") {
      val vid = UUID.randomUUID()
      val t1 = now.minusSeconds(3600)
      val t2 = now.minusSeconds(1800)
      val t3 = now
      val r1 = OdometerReading(UUID.randomUUID(), vid, 100L, "gps", t1, now)
      val r2 = OdometerReading(UUID.randomUUID(), vid, 200L, "gps", t2, now)
      val r3 = OdometerReading(UUID.randomUUID(), vid, 300L, "gps", t3, now)
      for {
        repo     <- ZIO.service[OdometerRepository]
        _        <- repo.saveReading(r1) *> repo.saveReading(r2) *> repo.saveReading(r3)
        readings <- repo.getReadings(vid, t1, t2)
      } yield assertTrue(readings.length == 2)
    }.provide(InMemoryOdometerRepo.live),

    test("saveDailyMileage + getDailyMileage") {
      val vid = UUID.randomUUID()
      val dm = DailyMileage(vid, today, 1000L, 1086L, 86L, now)
      for {
        repo   <- ZIO.service[OdometerRepository]
        _      <- repo.saveDailyMileage(dm)
        result <- repo.getDailyMileage(vid, today)
      } yield assertTrue(result.isDefined, result.get.distanceKm == 86L)
    }.provide(InMemoryOdometerRepo.live),

    test("getDailyMileageRange") {
      val vid = UUID.randomUUID()
      val dm1 = DailyMileage(vid, today.minusDays(1), 1000L, 1050L, 50L, now)
      val dm2 = DailyMileage(vid, today, 1050L, 1136L, 86L, now)
      for {
        repo  <- ZIO.service[OdometerRepository]
        _     <- repo.saveDailyMileage(dm1) *> repo.saveDailyMileage(dm2)
        range <- repo.getDailyMileageRange(vid, today.minusDays(1), today)
      } yield assertTrue(range.length == 2)
    }.provide(InMemoryOdometerRepo.live),

    test("saveEngineHours + getLatestEngineHours") {
      val vid = UUID.randomUUID()
      val eh1 = EngineHoursReading(vid, 500, "can", now.minusSeconds(60))
      val eh2 = EngineHoursReading(vid, 510, "can", now)
      for {
        repo   <- ZIO.service[OdometerRepository]
        _      <- repo.saveEngineHours(eh1) *> repo.saveEngineHours(eh2)
        latest <- repo.getLatestEngineHours(vid)
      } yield assertTrue(latest.isDefined, latest.get.engineHours == 510)
    }.provide(InMemoryOdometerRepo.live)
  )

  val templateSuite = suite("TemplateRepository")(
    test("create + findById") {
      val tId = UUID.randomUUID()
      val template = makeTemplate(id = tId, name = "Замена масла")
      for {
        repo  <- ZIO.service[TemplateRepository]
        _     <- repo.create(template)
        found <- repo.findById(tId)
      } yield assertTrue(found.isDefined, found.get.name == "Замена масла")
    }.provide(InMemoryTemplateRepo.live),

    test("findByCompany — только свои шаблоны") {
      val t1 = makeTemplate(name = "T1")
      val t2 = makeTemplate(name = "T2")
      val t3 = makeTemplate(company = UUID.randomUUID(), name = "T3")
      for {
        repo  <- ZIO.service[TemplateRepository]
        _     <- repo.create(t1) *> repo.create(t2) *> repo.create(t3)
        found <- repo.findByCompany(companyId, None)
      } yield assertTrue(found.length == 2)
    }.provide(InMemoryTemplateRepo.live),

    test("existsByName — дубликат") {
      val template = makeTemplate(name = "Unique")
      for {
        repo   <- ZIO.service[TemplateRepository]
        _      <- repo.create(template)
        exists <- repo.existsByName(companyId, "Unique")
        notEx  <- repo.existsByName(companyId, "NonExistent")
      } yield assertTrue(exists, !notEx)
    }.provide(InMemoryTemplateRepo.live),

    test("delete — удаление шаблона") {
      val tId = UUID.randomUUID()
      val template = makeTemplate(id = tId, name = "ToDelete")
      for {
        repo  <- ZIO.service[TemplateRepository]
        _     <- repo.create(template)
        _     <- repo.delete(tId)
        found <- repo.findById(tId)
      } yield assertTrue(found.isEmpty)
    }.provide(InMemoryTemplateRepo.live)
  )

  val scheduleSuite = suite("ScheduleRepository")(
    test("create + findById") {
      val sId = UUID.randomUUID()
      val schedule = makeSchedule(id = sId)
      for {
        repo  <- ZIO.service[ScheduleRepository]
        _     <- repo.create(schedule)
        found <- repo.findById(sId)
      } yield assertTrue(found.isDefined, found.get.status == ScheduleStatus.Active)
    }.provide(InMemoryScheduleRepo.live),

    test("findActiveByCompany — только активные") {
      val s1 = makeSchedule(status = ScheduleStatus.Active)
      val s2 = makeSchedule(status = ScheduleStatus.Paused)
      for {
        repo   <- ZIO.service[ScheduleRepository]
        _      <- repo.create(s1) *> repo.create(s2)
        active <- repo.findActiveByCompany(companyId)
      } yield assertTrue(active.length == 1)
    }.provide(InMemoryScheduleRepo.live),

    test("updateMileage — обновление текущего пробега") {
      val sId = UUID.randomUUID()
      val schedule = makeSchedule(id = sId, currentMileageKm = 100000L, nextMileageKm = Some(115000L))
      for {
        repo  <- ZIO.service[ScheduleRepository]
        _     <- repo.create(schedule)
        _     <- repo.updateMileage(vehicleId, 105000L, Some(10000L))
        found <- repo.findById(sId)
      } yield assertTrue(found.get.currentMileageKm == 105000L)
    }.provide(InMemoryScheduleRepo.live),

    test("updateStatus — изменение статуса") {
      val sId = UUID.randomUUID()
      val schedule = makeSchedule(id = sId, status = ScheduleStatus.Active)
      for {
        repo  <- ZIO.service[ScheduleRepository]
        _     <- repo.create(schedule)
        _     <- repo.updateStatus(sId, ScheduleStatus.Overdue)
        found <- repo.findById(sId)
      } yield assertTrue(found.get.status == ScheduleStatus.Overdue)
    }.provide(InMemoryScheduleRepo.live),

    test("resetReminderFlags — сброс флагов") {
      val sId = UUID.randomUUID()
      val schedule = makeSchedule(id = sId).copy(reminderFirstSent = true)
      for {
        repo  <- ZIO.service[ScheduleRepository]
        _     <- repo.create(schedule)
        _     <- repo.resetReminderFlags(sId)
        found <- repo.findById(sId)
      } yield assertTrue(!found.get.reminderFirstSent)
    }.provide(InMemoryScheduleRepo.live)
  )

  val domainSuite = suite("Domain — enums корректны")(
    test("IntervalType — все значения") {
      val count = IntervalType.values.length
      assertTrue(count == 4)
    },

    test("ScheduleStatus — все значения") {
      val statuses = ScheduleStatus.values.map(_.toString).toSet
      assertTrue(
        statuses.contains("Active"),
        statuses.contains("Paused"),
        statuses.contains("Overdue"),
        statuses.contains("Completed")
      )
    },

    test("ServicePriority — все 4 значения") {
      val count = ServicePriority.values.length
      assertTrue(count == 4)
    },

    test("ServiceType — включает основные типы") {
      val types = ServiceType.values.map(_.toString).toSet
      assertTrue(
        types.contains("Scheduled"),
        types.contains("Unscheduled"),
        types.contains("Emergency"),
        types.contains("Inspection")
      )
    }
  )
