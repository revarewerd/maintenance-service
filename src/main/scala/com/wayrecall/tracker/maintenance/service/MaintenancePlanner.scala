package com.wayrecall.tracker.maintenance.service

import zio.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.repository.{TemplateRepository, ScheduleRepository}
import com.wayrecall.tracker.maintenance.cache.MaintenanceCache
import com.wayrecall.tracker.maintenance.kafka.MaintenanceEventProducer
import java.util.UUID
import java.time.{Instant, LocalDate}

// ============================================================
// MAINTENANCE PLANNER — планирование ТО
//
// Создание расписаний ТО на основе шаблонов,
// обновление после выполнения ТО, пауза/возобновление.
// ============================================================

trait MaintenancePlanner:
  /** Создать расписание ТО для транспорта на основе шаблона */
  def createSchedule(companyId: UUID, request: CreateScheduleRequest): Task[MaintenanceSchedule]
  /** Обновить расписание после выполнения ТО */
  def updateScheduleAfterService(scheduleId: UUID, serviceRecord: ServiceRecord): Task[Unit]
  /** Приостановить расписание */
  def pauseSchedule(scheduleId: UUID): Task[Unit]
  /** Возобновить расписание */
  def resumeSchedule(scheduleId: UUID): Task[Unit]

case class MaintenancePlannerLive(
  templateRepo: TemplateRepository,
  scheduleRepo: ScheduleRepository,
  intervalCalc: IntervalCalculator,
  cache: MaintenanceCache,
  producer: MaintenanceEventProducer
) extends MaintenancePlanner:

  override def createSchedule(companyId: UUID, request: CreateScheduleRequest): Task[MaintenanceSchedule] =
    for {
      // Загружаем шаблон
      templateOpt <- templateRepo.findById(request.templateId)
      template    <- ZIO.fromOption(templateOpt)
                       .orElseFail(new RuntimeException(s"Шаблон не найден: ${request.templateId}"))

      // Рассчитываем следующее ТО
      nextService <- intervalCalc.calculateNextService(
        template,
        request.currentMileageKm,
        request.currentEngineHours.getOrElse(0),
        request.lastServiceDate,
        request.lastServiceMileageKm,
        request.lastServiceEngineHours
      )

      // Рассчитываем оставшееся
      remainingKm   = nextService.nextMileageKm.map(_ - request.currentMileageKm)
      remainingHours = nextService.nextEngineHours.map(_ - request.currentEngineHours.getOrElse(0))
      remainingDays  = nextService.nextDate.map(d => java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d).toInt)

      now = Instant.now()
      schedule = MaintenanceSchedule(
        id                        = UUID.randomUUID(),
        companyId                 = companyId,
        vehicleId                 = request.vehicleId,
        templateId                = request.templateId,
        templateName              = template.name,
        intervalType              = template.intervalType,
        intervalMileageKm         = template.intervalMileageKm,
        intervalEngineHours       = template.intervalEngineHours,
        intervalDays              = template.intervalDays,
        priority                  = template.priority,
        status                    = ScheduleStatus.Active,
        currentMileageKm          = request.currentMileageKm,
        currentEngineHours        = request.currentEngineHours.getOrElse(0),
        lastServiceDate           = request.lastServiceDate,
        lastServiceMileageKm      = request.lastServiceMileageKm,
        lastServiceEngineHours    = request.lastServiceEngineHours,
        nextServiceMileageKm      = nextService.nextMileageKm,
        nextServiceEngineHours    = nextService.nextEngineHours,
        nextServiceDate           = nextService.nextDate,
        remainingKm               = remainingKm,
        remainingEngineHours      = remainingHours,
        remainingDays             = remainingDays,
        reminderFirstSent         = false,
        reminderSecondSent        = false,
        reminderThirdSent         = false,
        reminderOverdueSent       = false,
        createdAt                 = now,
        updatedAt                 = now
      )

      // Сохраняем в БД
      _ <- scheduleRepo.create(schedule)
      // Инвалидируем кэш расписаний для этого ТС
      _ <- cache.invalidateSchedules(request.vehicleId)
      // Публикуем событие
      _ <- producer.publish(MaintenanceEvent.ScheduleCreated(
        schedule.id, schedule.vehicleId, companyId, template.name
      ))
      _ <- ZIO.logInfo(s"Создано расписание ТО: id=${schedule.id}, vehicle=${request.vehicleId}, шаблон='${template.name}'")
    } yield schedule

  override def updateScheduleAfterService(scheduleId: UUID, serviceRecord: ServiceRecord): Task[Unit] =
    for {
      scheduleOpt <- scheduleRepo.findById(scheduleId)
      schedule    <- ZIO.fromOption(scheduleOpt)
                       .orElseFail(new RuntimeException(s"Расписание не найдено: $scheduleId"))
      templateOpt <- templateRepo.findById(schedule.templateId)
      template    <- ZIO.fromOption(templateOpt)
                       .orElseFail(new RuntimeException(s"Шаблон не найден: ${schedule.templateId}"))

      // Рассчитываем следующее ТО от текущего пробега/даты
      nextService <- intervalCalc.calculateNextService(
        template,
        serviceRecord.mileageKm,
        serviceRecord.engineHours.getOrElse(schedule.currentEngineHours),
        Some(serviceRecord.serviceDate),
        Some(serviceRecord.mileageKm),
        serviceRecord.engineHours
      )

      // Если вручную указано следующее ТО — используем его
      finalNextMileage = serviceRecord.nextServiceMileageKm.orElse(nextService.nextMileageKm)
      finalNextDate    = serviceRecord.nextServiceDate.orElse(nextService.nextDate)

      _ <- scheduleRepo.updateAfterService(scheduleId, finalNextMileage, finalNextDate, nextService.nextEngineHours)
      _ <- scheduleRepo.resetReminderFlags(scheduleId)
      _ <- cache.invalidateSchedules(schedule.vehicleId)
      _ <- ZIO.logInfo(s"Расписание обновлено после ТО: id=$scheduleId, nextMileage=$finalNextMileage, nextDate=$finalNextDate")
    } yield ()

  override def pauseSchedule(scheduleId: UUID): Task[Unit] =
    for {
      scheduleOpt <- scheduleRepo.findById(scheduleId)
      schedule    <- ZIO.fromOption(scheduleOpt).orElseFail(new RuntimeException(s"Расписание не найдено: $scheduleId"))
      _           <- scheduleRepo.updateStatus(scheduleId, ScheduleStatus.Paused)
      _           <- cache.invalidateSchedules(schedule.vehicleId)
      _           <- ZIO.logInfo(s"Расписание приостановлено: id=$scheduleId")
    } yield ()

  override def resumeSchedule(scheduleId: UUID): Task[Unit] =
    for {
      scheduleOpt <- scheduleRepo.findById(scheduleId)
      schedule    <- ZIO.fromOption(scheduleOpt).orElseFail(new RuntimeException(s"Расписание не найдено: $scheduleId"))
      _           <- scheduleRepo.updateStatus(scheduleId, ScheduleStatus.Active)
      _           <- cache.invalidateSchedules(schedule.vehicleId)
      _           <- ZIO.logInfo(s"Расписание возобновлено: id=$scheduleId")
    } yield ()

object MaintenancePlannerLive:
  val live: ZLayer[TemplateRepository & ScheduleRepository & IntervalCalculator & MaintenanceCache & MaintenanceEventProducer, Nothing, MaintenancePlanner] =
    ZLayer.fromFunction(MaintenancePlannerLive(_, _, _, _, _))
