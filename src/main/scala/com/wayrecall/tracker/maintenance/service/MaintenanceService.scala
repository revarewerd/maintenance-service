package com.wayrecall.tracker.maintenance.service

import zio.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.repository.*
import com.wayrecall.tracker.maintenance.cache.MaintenanceCache
import com.wayrecall.tracker.maintenance.kafka.MaintenanceEventProducer
import java.util.UUID
import java.time.{Instant, LocalDate}

// ============================================================
// MAINTENANCE SERVICE — фасад бизнес-логики
//
// Объединяет все операции ТО:
// - CRUD шаблонов
// - Управление расписаниями
// - Регистрация выполненных ТО
// - Обзоры по транспорту и компании
// ============================================================

trait MaintenanceService:
  // ---- Шаблоны ----
  def createTemplate(companyId: UUID, request: CreateTemplateRequest): Task[MaintenanceTemplate]
  def getTemplate(id: UUID): Task[Option[MaintenanceTemplate]]
  def listTemplates(companyId: UUID, vehicleType: Option[String]): Task[List[MaintenanceTemplate]]
  def updateTemplate(id: UUID, request: CreateTemplateRequest): Task[Unit]
  def deleteTemplate(id: UUID): Task[Unit]

  // ---- Расписания ----
  def createSchedule(companyId: UUID, request: CreateScheduleRequest): Task[MaintenanceSchedule]
  def getVehicleSchedules(vehicleId: UUID): Task[List[MaintenanceSchedule]]
  def getVehicleOverview(vehicleId: UUID): Task[VehicleMaintenanceOverview]
  def pauseSchedule(scheduleId: UUID): Task[Unit]
  def resumeSchedule(scheduleId: UUID): Task[Unit]

  // ---- Записи о ТО ----
  def recordService(userId: UUID, request: RecordServiceRequest): Task[ServiceRecord]
  def getServiceHistory(vehicleId: UUID, limit: Int, offset: Int): Task[ServiceHistoryPage]
  def getServiceRecord(id: UUID): Task[Option[ServiceRecord]]

  // ---- Обзоры ----
  def getCompanyOverview(companyId: UUID): Task[CompanyMaintenanceOverview]

case class MaintenanceServiceLive(
  templateRepo: TemplateRepository,
  scheduleRepo: ScheduleRepository,
  serviceRecordRepo: ServiceRecordRepository,
  odometerRepo: OdometerRepository,
  planner: MaintenancePlanner,
  mileageTracker: MileageTracker,
  cache: MaintenanceCache,
  producer: MaintenanceEventProducer
) extends MaintenanceService:

  // ---- Шаблоны ----

  override def createTemplate(companyId: UUID, request: CreateTemplateRequest): Task[MaintenanceTemplate] =
    for {
      // Валидация интервалов
      _ <- validateIntervalRequest(request)
      now = Instant.now()
      templateId = UUID.randomUUID()
      items = request.items.zipWithIndex.map { case (item, idx) =>
        MaintenanceItem(UUID.randomUUID(), templateId, item.name, item.description, item.partNumber, item.estimatedCostRub, item.sortOrder)
      }
      template = MaintenanceTemplate(
        id = templateId, companyId = companyId,
        name = request.name, description = request.description,
        vehicleType = request.vehicleType,
        intervalType = request.intervalType,
        intervalMileageKm = request.intervalMileageKm,
        intervalEngineHours = request.intervalEngineHours,
        intervalDays = request.intervalDays,
        priority = request.priority,
        estimatedDurationMinutes = request.estimatedDurationMinutes,
        estimatedCostRub = request.estimatedCostRub,
        items = items,
        reminders = request.reminders,
        isActive = true,
        createdAt = now, updatedAt = now
      )
      _ <- templateRepo.create(template)
      _ <- ZIO.logInfo(s"Создан шаблон ТО: id=$templateId, name='${request.name}'")
    } yield template

  override def getTemplate(id: UUID): Task[Option[MaintenanceTemplate]] =
    templateRepo.findById(id)

  override def listTemplates(companyId: UUID, vehicleType: Option[String]): Task[List[MaintenanceTemplate]] =
    templateRepo.findByCompany(companyId, vehicleType)

  override def updateTemplate(id: UUID, request: CreateTemplateRequest): Task[Unit] =
    for {
      existingOpt <- templateRepo.findById(id)
      existing    <- ZIO.fromOption(existingOpt).orElseFail(new RuntimeException(s"Шаблон не найден: $id"))
      updated = existing.copy(
        name = request.name, description = request.description,
        vehicleType = request.vehicleType,
        intervalType = request.intervalType,
        intervalMileageKm = request.intervalMileageKm,
        intervalEngineHours = request.intervalEngineHours,
        intervalDays = request.intervalDays,
        priority = request.priority,
        estimatedDurationMinutes = request.estimatedDurationMinutes,
        estimatedCostRub = request.estimatedCostRub,
        updatedAt = Instant.now()
      )
      _ <- templateRepo.update(updated)
    } yield ()

  override def deleteTemplate(id: UUID): Task[Unit] =
    templateRepo.delete(id)

  // ---- Расписания ----

  override def createSchedule(companyId: UUID, request: CreateScheduleRequest): Task[MaintenanceSchedule] =
    planner.createSchedule(companyId, request)

  override def getVehicleSchedules(vehicleId: UUID): Task[List[MaintenanceSchedule]] =
    scheduleRepo.findByVehicle(vehicleId)

  override def getVehicleOverview(vehicleId: UUID): Task[VehicleMaintenanceOverview] =
    for {
      schedules <- scheduleRepo.findByVehicle(vehicleId)
      records   <- serviceRecordRepo.findByVehicle(vehicleId, 5, 0) // Последние 5 ТО
      mileage   <- mileageTracker.getCurrentMileage(vehicleId)
      scheduleSummaries = schedules.map(s => ScheduleSummary(
        s.id, s.templateName, s.status, s.priority, s.remainingKm, s.remainingDays, s.nextServiceDate
      ))
      serviceSummaries = records.map(r => ServiceSummary(
        r.id, r.description, r.serviceDate, r.mileageKm, r.totalCostRub, r.serviceType
      ))
    } yield VehicleMaintenanceOverview(
      vehicleId = vehicleId,
      schedules = scheduleSummaries,
      recentServices = serviceSummaries,
      currentMileageKm = mileage,
      overdueCount = schedules.count(_.status == ScheduleStatus.Overdue),
      upcomingCount = schedules.count(s => s.status == ScheduleStatus.Active && s.remainingKm.exists(_ <= 1000))
    )

  override def pauseSchedule(scheduleId: UUID): Task[Unit] =
    planner.pauseSchedule(scheduleId)

  override def resumeSchedule(scheduleId: UUID): Task[Unit] =
    planner.resumeSchedule(scheduleId)

  // ---- Записи о ТО ----

  override def recordService(userId: UUID, request: RecordServiceRequest): Task[ServiceRecord] =
    val now = Instant.now()
    val recordId = UUID.randomUUID()
    val items = request.items.map { item =>
      ServiceItemRecord(UUID.randomUUID(), recordId, item.name, item.partNumber, item.quantity, item.costRub, item.notes)
    }
    // Определяем companyId из расписания или из vehicleId (TODO: lookup)
    val companyId = UUID.nameUUIDFromBytes(s"company-default".getBytes) // Placeholder
    val record = ServiceRecord(
        id = recordId, companyId = companyId, vehicleId = request.vehicleId,
        scheduleId = request.scheduleId, serviceType = request.serviceType,
        description = request.description, mileageKm = request.mileageKm,
        engineHours = request.engineHours, serviceDate = request.serviceDate,
        performedBy = request.performedBy, workshop = request.workshop,
        totalCostRub = request.totalCostRub, items = items,
        notes = request.notes, attachments = Nil,
        nextServiceMileageKm = request.nextServiceMileageKm,
        nextServiceDate = request.nextServiceDate,
        createdBy = userId, createdAt = now, updatedAt = now
      )
    for {
      _ <- serviceRecordRepo.create(record)

      // Обновляем расписание если привязано
      _ <- (request.scheduleId match
        case Some(sid) => planner.updateScheduleAfterService(sid, record)
        case None      => ZIO.unit
      )

      // Публикуем событие
      _ <- producer.publish(MaintenanceEvent.ServiceCompleted(
        recordId, request.vehicleId, companyId, request.mileageKm, request.serviceDate
      ))
      _ <- ZIO.logInfo(s"Зарегистрировано ТО: id=$recordId, vehicle=${request.vehicleId}, mileage=${request.mileageKm}")
    } yield record

  override def getServiceHistory(vehicleId: UUID, limit: Int, offset: Int): Task[ServiceHistoryPage] =
    for {
      records <- serviceRecordRepo.findByVehicle(vehicleId, limit, offset)
      total   <- serviceRecordRepo.countByVehicle(vehicleId)
    } yield ServiceHistoryPage(records, total, limit, offset)

  override def getServiceRecord(id: UUID): Task[Option[ServiceRecord]] =
    serviceRecordRepo.findById(id)

  // ---- Обзоры ----

  override def getCompanyOverview(companyId: UUID): Task[CompanyMaintenanceOverview] =
    for {
      allSchedules <- scheduleRepo.findActiveByCompany(companyId)
      // Группируем по vehicleId для обзоров
      vehicleIds = allSchedules.map(_.vehicleId).distinct
      vehicleOverviews <- ZIO.foreach(vehicleIds)(getVehicleOverview)
      totalCost <- serviceRecordRepo.totalCostForPeriod(
        companyId, LocalDate.now().withDayOfMonth(1), LocalDate.now()
      )
    } yield CompanyMaintenanceOverview(
      companyId = companyId,
      totalSchedules = allSchedules.length,
      activeSchedules = allSchedules.count(_.status == ScheduleStatus.Active),
      overdueCount = allSchedules.count(_.status == ScheduleStatus.Overdue),
      upcomingThisWeek = allSchedules.count(s => s.remainingDays.exists(_ <= 7) && s.remainingDays.exists(_ > 0)),
      totalCostThisMonth = totalCost,
      vehicleOverviews = vehicleOverviews
    )

  // ---- Валидация ----

  private def validateIntervalRequest(request: CreateTemplateRequest): Task[Unit] =
    request.intervalType match
      case IntervalType.Mileage =>
        ZIO.when(request.intervalMileageKm.isEmpty)(
          ZIO.fail(new RuntimeException("Для интервала по пробегу укажите intervalMileageKm"))
        ).unit
      case IntervalType.EngineHours =>
        ZIO.when(request.intervalEngineHours.isEmpty)(
          ZIO.fail(new RuntimeException("Для интервала по моточасам укажите intervalEngineHours"))
        ).unit
      case IntervalType.Calendar =>
        ZIO.when(request.intervalDays.isEmpty)(
          ZIO.fail(new RuntimeException("Для календарного интервала укажите intervalDays"))
        ).unit
      case IntervalType.Combined =>
        ZIO.when(request.intervalMileageKm.isEmpty && request.intervalDays.isEmpty)(
          ZIO.fail(new RuntimeException("Для комбинированного интервала укажите хотя бы intervalMileageKm или intervalDays"))
        ).unit

object MaintenanceServiceLive:
  val live: ZLayer[
    TemplateRepository & ScheduleRepository & ServiceRecordRepository &
    OdometerRepository & MaintenancePlanner & MileageTracker &
    MaintenanceCache & MaintenanceEventProducer,
    Nothing,
    MaintenanceService
  ] = ZLayer.fromFunction(MaintenanceServiceLive(_, _, _, _, _, _, _, _))
