package com.wayrecall.tracker.maintenance.service

import zio.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.cache.MaintenanceCache
import com.wayrecall.tracker.maintenance.repository.{ScheduleRepository, OdometerRepository}
import java.util.UUID
import java.time.Instant

// ============================================================
// MILEAGE TRACKER — отслеживание пробега и моточасов из GPS
//
// Обрабатывает GPS события (из Kafka):
// 1. Извлекает odometer и engine_hours
// 2. Сохраняет показание одометра в БД
// 3. Обновляет кэшированный пробег в Redis
// 4. Обновляет расписания ТО (remaining_km)
// 5. Проверяет пороги напоминаний
//
// Использует распределённую блокировку (Redis) для предотвращения
// конкурентной обработки пробега одного транспорта.
// ============================================================

trait MileageTracker:
  /** Обработать GPS событие — извлечь и обновить пробег */
  def processGpsEvent(event: GpsEvent): Task[Unit]
  /** Получить текущий пробег из кэша или БД */
  def getCurrentMileage(vehicleId: UUID): Task[Long]

case class MileageTrackerLive(
  cache: MaintenanceCache,
  scheduleRepo: ScheduleRepository,
  odometerRepo: OdometerRepository,
  reminderEngine: ReminderEngine
) extends MileageTracker:

  override def processGpsEvent(event: GpsEvent): Task[Unit] =
    // vehicleId может прийти из GPS события (маппинг device→vehicle)
    event.vehicleId match
      case None => ZIO.logDebug(s"GPS событие без vehicleId, IMEI=${event.imei} — пропускаем")
      case Some(vId) =>
        val vehicleId = UUID.nameUUIDFromBytes(s"vehicle-$vId".getBytes)
        for {
          // Захватываем блокировку — один consumer за раз для одного ТС
          locked <- cache.acquireMileageLock(vehicleId)
          _ <- if !locked then
                ZIO.logDebug(s"Блокировка пробега уже захвачена: vehicleId=$vehicleId")
              else
                processWithLock(vehicleId, event).ensuring(cache.releaseMileageLock(vehicleId).orDie)
        } yield ()

  private def processWithLock(vehicleId: UUID, event: GpsEvent): Task[Unit] =
    for {
      // Обработка одометра
      _ <- (event.odometer match
        case Some(odometerMeters) =>
          val mileageKm = odometerMeters / 1000 // Одометр в метрах → км
          val reading = OdometerReading(
            id = UUID.randomUUID(),
            vehicleId = vehicleId,
            mileageKm = mileageKm,
            source = "gps",
            recordedAt = event.timestamp,
            createdAt = Instant.now()
          )
          for {
            // Сохраняем показание
            _ <- odometerRepo.saveReading(reading)
            // Обновляем кэш
            _ <- cache.setMileage(vehicleId, mileageKm)
            // Обновляем расписания — пересчитываем remaining_km
            schedules <- scheduleRepo.findByVehicle(vehicleId)
            _ <- ZIO.foreach(schedules.filter(s => s.status == ScheduleStatus.Active || s.status == ScheduleStatus.Overdue)) { schedule =>
              val remainingKm = schedule.nextServiceMileageKm.map(_ - mileageKm)
              scheduleRepo.updateMileage(vehicleId, mileageKm, remainingKm) *>
                // Проверяем пороги напоминаний
                reminderEngine.checkAndSendReminders(schedule.copy(currentMileageKm = mileageKm, remainingKm = remainingKm))
                  .catchAll(err => ZIO.logWarning(s"Ошибка проверки порогов: ${err}"))
            }
          } yield ()
        case None => ZIO.unit
      )

      // Обработка моточасов
      _ <- (event.engineHours match
        case Some(hours) =>
          val ehReading = EngineHoursReading(vehicleId, hours, "gps", event.timestamp)
          for {
            _ <- odometerRepo.saveEngineHours(ehReading)
            _ <- scheduleRepo.updateEngineHours(vehicleId, hours, None) // remainingHours считается в IntervalCalculator
          } yield ()
        case None => ZIO.unit
      )
    } yield ()

  override def getCurrentMileage(vehicleId: UUID): Task[Long] =
    cache.getMileage(vehicleId).flatMap {
      case Some(mileage) => ZIO.succeed(mileage)
      case None =>
        odometerRepo.getLatest(vehicleId).flatMap {
          case Some(reading) =>
            cache.setMileage(vehicleId, reading.mileageKm).as(reading.mileageKm)
          case None => ZIO.succeed(0L)
        }
    }

object MileageTrackerLive:
  val live: ZLayer[MaintenanceCache & ScheduleRepository & OdometerRepository & ReminderEngine, Nothing, MileageTracker] =
    ZLayer.fromFunction(MileageTrackerLive(_, _, _, _))
