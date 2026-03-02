package com.wayrecall.tracker.maintenance.service

import zio.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.cache.MaintenanceCache
import com.wayrecall.tracker.maintenance.config.ReminderThresholdsConfig
import com.wayrecall.tracker.maintenance.kafka.MaintenanceEventProducer
import com.wayrecall.tracker.maintenance.repository.{ScheduleRepository, TemplateRepository}
import java.util.UUID
import java.time.Instant

// ============================================================
// REMINDER ENGINE — движок напоминаний о ТО
//
// Проверяет пороги и отправляет напоминания:
// 1. За 500 км / 7 дней — первое напоминание
// 2. За 100 км / 1 день — второе напоминание
// 3. Просрочено           — напоминание об overdue
//
// Идемпотентность: через Redis флаги (maint:reminder:*)
// Каждое напоминание отправляется ровно один раз.
// ============================================================

trait ReminderEngine:
  /** Проверить пороги и отправить напоминания для расписания */
  def checkAndSendReminders(schedule: MaintenanceSchedule): Task[List[ReminderSent]]
  /** Проверить календарные напоминания (для cron job) */
  def processCalendarReminders(): Task[Int]

case class ReminderEngineLive(
  cache: MaintenanceCache,
  scheduleRepo: ScheduleRepository,
  producer: MaintenanceEventProducer,
  thresholds: ReminderThresholdsConfig
) extends ReminderEngine:

  override def checkAndSendReminders(schedule: MaintenanceSchedule): Task[List[ReminderSent]] =
    for {
      sent <- ZIO.collectAll(List(
        // Проверяем порог по пробегу — первое напоминание (500 км)
        checkKmThreshold(schedule, "first", thresholds.firstKm, schedule.reminderFirstSent),
        // Второе напоминание (100 км)
        checkKmThreshold(schedule, "second", thresholds.secondKm, schedule.reminderSecondSent),
        // Проверяем просрочку
        checkOverdue(schedule),
      ))
    } yield sent.flatten

  override def processCalendarReminders(): Task[Int] =
    for {
      // Ищем расписания, у которых до ТО осталось <= 7 дней
      approaching <- scheduleRepo.findApproachingThreshold(Long.MaxValue, thresholds.firstDays)
      sent <- ZIO.foreach(approaching) { schedule =>
        checkDaysThreshold(schedule, "first_days", thresholds.firstDays, schedule.reminderFirstSent) *>
          checkDaysThreshold(schedule, "second_days", thresholds.secondDays, schedule.reminderSecondSent)
      }
      count = sent.flatten.length
      _ <- ZIO.when(count > 0)(ZIO.logInfo(s"Отправлено $count календарных напоминаний"))
    } yield count

  /** Проверяет порог по пробегу и отправляет напоминание если нужно */
  private def checkKmThreshold(
    schedule: MaintenanceSchedule,
    level: String,
    thresholdKm: Long,
    alreadySent: Boolean
  ): Task[Option[ReminderSent]] =
    if alreadySent then ZIO.succeed(None)
    else
      schedule.remainingKm match
        case Some(remaining) if remaining <= thresholdKm && remaining > 0 =>
          sendReminder(schedule, s"threshold_km_$thresholdKm", level, Some(remaining), None)
        case _ => ZIO.succeed(None)

  /** Проверяет порог по дням */
  private def checkDaysThreshold(
    schedule: MaintenanceSchedule,
    level: String,
    thresholdDays: Int,
    alreadySent: Boolean
  ): Task[Option[ReminderSent]] =
    if alreadySent then ZIO.succeed(None)
    else
      schedule.remainingDays match
        case Some(remaining) if remaining <= thresholdDays && remaining > 0 =>
          sendReminder(schedule, s"threshold_days_$thresholdDays", level, None, Some(remaining))
        case _ => ZIO.succeed(None)

  /** Проверяет просрочку */
  private def checkOverdue(schedule: MaintenanceSchedule): Task[Option[ReminderSent]] =
    if schedule.reminderOverdueSent then ZIO.succeed(None)
    else
      val isOverdue = schedule.remainingKm.exists(_ <= 0) ||
                      schedule.remainingDays.exists(_ <= 0) ||
                      schedule.remainingEngineHours.exists(_ <= 0)
      if !isOverdue then ZIO.succeed(None)
      else
        for {
          // Проверяем идемпотентность через Redis
          sent <- cache.isReminderSent(schedule.id, "overdue", "0")
          result <- if sent then ZIO.succeed(None)
                   else
                     for {
                       _ <- cache.markReminderSent(schedule.id, "overdue", "0")
                       _ <- scheduleRepo.updateReminderFlag(schedule.id, "overdue")
                       _ <- scheduleRepo.updateStatus(schedule.id, ScheduleStatus.Overdue)
                       _ <- producer.publish(MaintenanceEvent.ServiceOverdue(
                         schedule.id, schedule.vehicleId, schedule.companyId,
                         schedule.remainingKm.map(r => -r),     // overdueKm — положительное значение
                         schedule.remainingDays.map(r => -r)
                       ))
                       _ <- ZIO.logInfo(s"Напоминание об overdue: schedule=${schedule.id}, vehicle=${schedule.vehicleId}")
                     } yield Some(ReminderSent(schedule.id, schedule.vehicleId, schedule.companyId, "overdue"))
        } yield result

  /** Отправка напоминания с проверкой идемпотентности */
  private def sendReminder(
    schedule: MaintenanceSchedule,
    thresholdType: String,
    flagLevel: String,
    remainingKm: Option[Long],
    remainingDays: Option[Int]
  ): Task[Option[ReminderSent]] =
    for {
      // Проверяем через Redis — не отправляли ли уже
      alreadySent <- cache.isReminderSent(schedule.id, thresholdType, "1")
      result <- if alreadySent then ZIO.succeed(None)
               else
                 for {
                   _ <- cache.markReminderSent(schedule.id, thresholdType, "1")
                   _ <- scheduleRepo.updateReminderFlag(schedule.id, flagLevel)
                   _ <- producer.publish(MaintenanceEvent.ServiceDueReminder(
                     schedule.id, schedule.vehicleId, schedule.companyId,
                     remainingKm, remainingDays, schedule.priority
                   ))
                   _ <- ZIO.logInfo(s"Напоминание о ТО: schedule=${schedule.id}, порог=$thresholdType")
                 } yield Some(ReminderSent(schedule.id, schedule.vehicleId, schedule.companyId, thresholdType))
    } yield result

object ReminderEngineLive:
  val live: ZLayer[MaintenanceCache & ScheduleRepository & MaintenanceEventProducer & ReminderThresholdsConfig, Nothing, ReminderEngine] =
    ZLayer.fromFunction(ReminderEngineLive(_, _, _, _))
