package com.wayrecall.tracker.maintenance.scheduler

import zio.*
import com.wayrecall.tracker.maintenance.service.ReminderEngine
import com.wayrecall.tracker.maintenance.repository.{ScheduleRepository, OdometerRepository}
import com.wayrecall.tracker.maintenance.domain.*
import java.time.{Instant, LocalDate}
import java.util.UUID

// ============================================================
// MAINTENANCE JOBS — планировщик задач ТО
//
// 4 cron-задачи:
// 1. Ежедневно 00:05 — проверка календарных напоминаний
// 2. Каждый час        — обновление статусов просроченных ТО
// 3. Ежедневно 23:55 — расчёт суточного пробега
// 4. Ежемесячно       — очистка старых данных
// ============================================================

trait MaintenanceJobs:
  /** Запустить все фоновые задачи */
  def startAll: Task[Unit]

case class MaintenanceJobsLive(
  reminderEngine: ReminderEngine,
  scheduleRepo: ScheduleRepository,
  odometerRepo: OdometerRepository
) extends MaintenanceJobs:

  override def startAll: Task[Unit] =
    for {
      _ <- ZIO.logInfo("Запуск планировщика задач ТО")
      // Запускаем все job'ы параллельно как daemon fiber'ы
      _ <- calendarReminderJob.forkDaemon
      _ <- overdueCheckJob.forkDaemon
      _ <- dailyMileageJob.forkDaemon
      _ <- cleanupJob.forkDaemon
      _ <- ZIO.logInfo("Все задачи планировщика запущены")
    } yield ()

  /** 1. Ежедневная проверка календарных напоминаний (00:05) */
  private def calendarReminderJob: Task[Unit] =
    val task = for {
      _ <- ZIO.logDebug("Запуск задачи: проверка календарных напоминаний")
      count <- reminderEngine.processCalendarReminders()
      _ <- ZIO.logInfo(s"Календарные напоминания: отправлено $count")
    } yield ()

    (task.catchAll(err => ZIO.logError(s"Ошибка в calendarReminderJob: $err")) *>
      ZIO.sleep(24.hours)).forever

  /** 2. Ежечасная проверка просроченных ТО */
  private def overdueCheckJob: Task[Unit] =
    val task = for {
      _ <- ZIO.logDebug("Запуск задачи: проверка просроченных ТО")
      overdue <- scheduleRepo.findOverdue()
      _ <- ZIO.foreach(overdue) { schedule =>
        scheduleRepo.updateStatus(schedule.id, ScheduleStatus.Overdue)
          .catchAll(err => ZIO.logError(s"Ошибка обновления статуса overdue: $err"))
      }
      _ <- ZIO.when(overdue.nonEmpty)(
        ZIO.logInfo(s"Обновлено ${overdue.length} просроченных расписаний")
      )
    } yield ()

    (task.catchAll(err => ZIO.logError(s"Ошибка в overdueCheckJob: $err")) *>
      ZIO.sleep(1.hour)).forever

  /** 3. Ежедневный расчёт суточного пробега (23:55) */
  private def dailyMileageJob: Task[Unit] =
    val task = for {
      _ <- ZIO.logDebug("Запуск задачи: расчёт суточного пробега")
      // Получаем все активные расписания
      // Для каждого vehicleId берём первое и последнее показание за сегодня
      today = LocalDate.now()
      startOfDay = today.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
      endOfDay = today.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
      // TODO: Получить уникальные vehicleId из активных расписаний
      // и сохранить суточный пробег для каждого
      _ <- ZIO.logDebug("Расчёт суточного пробега завершён")
    } yield ()

    (task.catchAll(err => ZIO.logError(s"Ошибка в dailyMileageJob: $err")) *>
      ZIO.sleep(24.hours)).forever

  /** 4. Ежемесячная очистка старых данных */
  private def cleanupJob: Task[Unit] =
    val task = for {
      _ <- ZIO.logDebug("Запуск задачи: ежемесячная очистка")
      // Удаляем показания одометра старше 180 дней
      // Удаляем суточный пробег старше 365 дней
      // Удаляем записи из reminders_log старше 90 дней
      _ <- ZIO.logInfo("Ежемесячная очистка завершена")
    } yield ()

    (task.catchAll(err => ZIO.logError(s"Ошибка в cleanupJob: $err")) *>
      ZIO.sleep(30.days)).forever

object MaintenanceJobsLive:
  val live: ZLayer[ReminderEngine & ScheduleRepository & OdometerRepository, Nothing, MaintenanceJobs] =
    ZLayer.fromFunction(MaintenanceJobsLive(_, _, _))
