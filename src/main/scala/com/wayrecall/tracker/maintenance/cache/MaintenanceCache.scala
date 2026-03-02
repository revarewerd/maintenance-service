package com.wayrecall.tracker.maintenance.cache

import zio.*
import zio.json.*
import com.wayrecall.tracker.maintenance.domain.*
import java.util.UUID

// ============================================================
// MAINTENANCE CACHE — In-memory кэширование через Ref
//
// Ключи (виртуальные, хранятся в Map):
//   maint:mileage:{vehicleId}                    — текущий пробег (км)
//   maint:schedules:{vehicleId}                  — кэш расписаний (JSON)
//   maint:reminder:{scheduleId}:{type}:{value}   — флаг напоминания
//   maint:lock:mileage:{vehicleId}               — блокировка
//
// TODO: При масштабировании заменить на Redis-реализацию
// ============================================================

trait MaintenanceCache:
  /** Получить кэшированный пробег */
  def getMileage(vehicleId: UUID): Task[Option[Long]]
  /** Установить пробег в кэш */
  def setMileage(vehicleId: UUID, mileageKm: Long): Task[Unit]
  /** Получить кэшированные расписания */
  def getSchedules(vehicleId: UUID): Task[Option[List[CachedSchedule]]]
  /** Установить расписания в кэш */
  def setSchedules(vehicleId: UUID, schedules: List[CachedSchedule]): Task[Unit]
  /** Инвалидировать кэш расписаний */
  def invalidateSchedules(vehicleId: UUID): Task[Unit]
  /** Проверить, было ли напоминание уже отправлено */
  def isReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Boolean]
  /** Пометить напоминание как отправленное */
  def markReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Unit]
  /** Попытка захватить блокировку пробега */
  def acquireMileageLock(vehicleId: UUID): Task[Boolean]
  /** Освободить блокировку пробега */
  def releaseMileageLock(vehicleId: UUID): Task[Unit]

case class MaintenanceCacheLive(store: Ref[Map[String, String]]) extends MaintenanceCache:

  private val MILEAGE_PREFIX   = "maint:mileage"
  private val SCHEDULES_PREFIX = "maint:schedules"
  private val REMINDER_PREFIX  = "maint:reminder"
  private val LOCK_PREFIX      = "maint:lock:mileage"

  override def getMileage(vehicleId: UUID): Task[Option[Long]] =
    store.get.map(_.get(s"$MILEAGE_PREFIX:$vehicleId").flatMap(_.toLongOption))

  override def setMileage(vehicleId: UUID, mileageKm: Long): Task[Unit] =
    store.update(_ + (s"$MILEAGE_PREFIX:$vehicleId" -> mileageKm.toString))

  override def getSchedules(vehicleId: UUID): Task[Option[List[CachedSchedule]]] =
    store.get.map(_.get(s"$SCHEDULES_PREFIX:$vehicleId")
      .flatMap(json => json.fromJson[List[CachedSchedule]].toOption))

  override def setSchedules(vehicleId: UUID, schedules: List[CachedSchedule]): Task[Unit] =
    store.update(_ + (s"$SCHEDULES_PREFIX:$vehicleId" -> schedules.toJson))

  override def invalidateSchedules(vehicleId: UUID): Task[Unit] =
    store.update(_ - s"$SCHEDULES_PREFIX:$vehicleId")

  override def isReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Boolean] =
    store.get.map(_.contains(s"$REMINDER_PREFIX:$scheduleId:$thresholdType:$thresholdValue"))

  override def markReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Unit] =
    store.update(_ + (s"$REMINDER_PREFIX:$scheduleId:$thresholdType:$thresholdValue" -> "1"))

  override def acquireMileageLock(vehicleId: UUID): Task[Boolean] =
    val key = s"$LOCK_PREFIX:$vehicleId"
    store.modify { m =>
      if m.contains(key) then (false, m)
      else (true, m + (key -> "locked"))
    }

  override def releaseMileageLock(vehicleId: UUID): Task[Unit] =
    store.update(_ - s"$LOCK_PREFIX:$vehicleId")

object MaintenanceCacheLive:
  val live: ULayer[MaintenanceCache] =
    ZLayer.fromZIO(Ref.make(Map.empty[String, String]).map(MaintenanceCacheLive(_)))
