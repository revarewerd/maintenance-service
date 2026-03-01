package com.wayrecall.tracker.maintenance.cache

import zio.*
import zio.redis.*
import zio.json.*
import com.wayrecall.tracker.maintenance.domain.*
import java.util.UUID

// ============================================================
// MAINTENANCE CACHE — Redis кэширование
//
// Ключи:
//   maint:mileage:{vehicleId}                    — текущий пробег (км)
//   maint:schedules:{vehicleId}                  — кэш расписаний (TTL 1h)
//   maint:reminder:{scheduleId}:{type}:{value}   — флаг напоминания (TTL 24h)
//   maint:lock:mileage:{vehicleId}               — распределённая блокировка (TTL 5s)
// ============================================================

trait MaintenanceCache:
  /** Получить кэшированный пробег */
  def getMileage(vehicleId: UUID): Task[Option[Long]]
  /** Установить пробег в кэш */
  def setMileage(vehicleId: UUID, mileageKm: Long): Task[Unit]
  /** Получить кэшированные расписания */
  def getSchedules(vehicleId: UUID): Task[Option[List[CachedSchedule]]]
  /** Установить расписания в кэш (TTL 1 час) */
  def setSchedules(vehicleId: UUID, schedules: List[CachedSchedule]): Task[Unit]
  /** Инвалидировать кэш расписаний */
  def invalidateSchedules(vehicleId: UUID): Task[Unit]
  /** Проверить, было ли напоминание уже отправлено */
  def isReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Boolean]
  /** Пометить напоминание как отправленное (TTL 24 часа) */
  def markReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Unit]
  /** Попытка захватить блокировку пробега (TTL 5 сек) */
  def acquireMileageLock(vehicleId: UUID): Task[Boolean]
  /** Освободить блокировку пробега */
  def releaseMileageLock(vehicleId: UUID): Task[Unit]

case class MaintenanceCacheLive(redis: Redis) extends MaintenanceCache:

  private val MILEAGE_PREFIX   = "maint:mileage"
  private val SCHEDULES_PREFIX = "maint:schedules"
  private val REMINDER_PREFIX  = "maint:reminder"
  private val LOCK_PREFIX      = "maint:lock:mileage"

  override def getMileage(vehicleId: UUID): Task[Option[Long]] =
    redis.get(s"$MILEAGE_PREFIX:$vehicleId").returning[String]
      .map(_.flatMap(_.toLongOption))
      .catchAll(_ => ZIO.succeed(None))

  override def setMileage(vehicleId: UUID, mileageKm: Long): Task[Unit] =
    redis.set(s"$MILEAGE_PREFIX:$vehicleId", mileageKm.toString).unit
      .catchAll(err => ZIO.logWarning(s"Не удалось записать пробег в кэш: $err"))

  override def getSchedules(vehicleId: UUID): Task[Option[List[CachedSchedule]]] =
    redis.get(s"$SCHEDULES_PREFIX:$vehicleId").returning[String]
      .map(_.flatMap(json => json.fromJson[List[CachedSchedule]].toOption))
      .catchAll(_ => ZIO.succeed(None))

  override def setSchedules(vehicleId: UUID, schedules: List[CachedSchedule]): Task[Unit] =
    val json = schedules.toJson
    (redis.set(s"$SCHEDULES_PREFIX:$vehicleId", json) *>
      redis.expire(s"$SCHEDULES_PREFIX:$vehicleId", 3600.seconds)).unit
      .catchAll(err => ZIO.logWarning(s"Не удалось записать расписания в кэш: $err"))

  override def invalidateSchedules(vehicleId: UUID): Task[Unit] =
    redis.del(s"$SCHEDULES_PREFIX:$vehicleId").unit
      .catchAll(err => ZIO.logWarning(s"Не удалось инвалидировать кэш расписаний: $err"))

  override def isReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Boolean] =
    redis.exists(s"$REMINDER_PREFIX:$scheduleId:$thresholdType:$thresholdValue")
      .map(_ > 0L)
      .catchAll(_ => ZIO.succeed(false))

  override def markReminderSent(scheduleId: UUID, thresholdType: String, thresholdValue: String): Task[Unit] =
    val key = s"$REMINDER_PREFIX:$scheduleId:$thresholdType:$thresholdValue"
    (redis.set(key, "1") *> redis.expire(key, 86400.seconds)).unit
      .catchAll(err => ZIO.logWarning(s"Не удалось пометить напоминание в кэше: $err"))

  override def acquireMileageLock(vehicleId: UUID): Task[Boolean] =
    val key = s"$LOCK_PREFIX:$vehicleId"
    // SET NX с TTL 5 сек — атомарная блокировка
    redis.set(key, "locked", expireTime = Some(zio.redis.api.SetExpireTime.Px(5000.millis)))
      .map(_ => true)
      .catchAll(_ => ZIO.succeed(false))

  override def releaseMileageLock(vehicleId: UUID): Task[Unit] =
    redis.del(s"$LOCK_PREFIX:$vehicleId").unit
      .catchAll(_ => ZIO.unit)

object MaintenanceCacheLive:
  val live: ZLayer[Redis, Nothing, MaintenanceCache] =
    ZLayer.fromFunction(MaintenanceCacheLive(_))
