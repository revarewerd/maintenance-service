package com.wayrecall.tracker.maintenance.service

import zio.*
import com.wayrecall.tracker.maintenance.domain.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

// ============================================================
// INTERVAL CALCULATOR — расчёт интервалов ТО
//
// Вычисляет:
// - Когда следующее ТО (по пробегу/моточасам/дням)
// - Сколько осталось до ТО
// - Какие пороги напоминаний достигнуты
// ============================================================

trait IntervalCalculator:
  /** Рассчитать дату/пробег следующего ТО */
  def calculateNextService(
    template: MaintenanceTemplate,
    currentMileage: Long,
    currentHours: Int,
    lastServiceDate: Option[LocalDate],
    lastServiceMileage: Option[Long],
    lastServiceHours: Option[Int]
  ): Task[NextServiceInfo]

  /** Рассчитать оставшееся до ТО */
  def calculateRemaining(schedule: MaintenanceSchedule): Task[RemainingInfo]

  /** Проверить, какие пороги напоминаний достигнуты */
  def checkThresholds(schedule: MaintenanceSchedule, thresholds: List[ReminderConfig]): Task[List[ThresholdReached]]

case class IntervalCalculatorLive() extends IntervalCalculator:

  override def calculateNextService(
    template: MaintenanceTemplate,
    currentMileage: Long,
    currentHours: Int,
    lastServiceDate: Option[LocalDate],
    lastServiceMileage: Option[Long],
    lastServiceHours: Option[Int]
  ): Task[NextServiceInfo] =
    ZIO.succeed {
      val nextMileage = for {
        interval <- template.intervalMileageKm
        base     = lastServiceMileage.getOrElse(currentMileage)
      } yield base + interval

      val nextHours = for {
        interval <- template.intervalEngineHours
        base     = lastServiceHours.getOrElse(currentHours)
      } yield base + interval

      val nextDate = for {
        interval <- template.intervalDays
        base     = lastServiceDate.getOrElse(LocalDate.now())
      } yield base.plusDays(interval.toLong)

      NextServiceInfo(nextMileage, nextHours, nextDate)
    }

  override def calculateRemaining(schedule: MaintenanceSchedule): Task[RemainingInfo] =
    ZIO.succeed {
      val remainingKm = schedule.nextServiceMileageKm.map(_ - schedule.currentMileageKm)
      val remainingHours = schedule.nextServiceEngineHours.map(_ - schedule.currentEngineHours)
      val remainingDays = schedule.nextServiceDate.map { nextDate =>
        ChronoUnit.DAYS.between(LocalDate.now(), nextDate).toInt
      }
      RemainingInfo(remainingKm, remainingHours, remainingDays)
    }

  override def checkThresholds(schedule: MaintenanceSchedule, thresholds: List[ReminderConfig]): Task[List[ThresholdReached]] =
    ZIO.succeed {
      thresholds.flatMap { threshold =>
        val kmReached = for {
          thresholdKm <- threshold.thresholdKm
          remainingKm <- schedule.remainingKm
          if remainingKm <= thresholdKm && remainingKm > 0
        } yield ThresholdReached(schedule.id, "km", thresholdKm.toString, remainingKm.toString)

        val daysReached = for {
          thresholdDays <- threshold.thresholdDays
          remainingDays <- schedule.remainingDays
          if remainingDays <= thresholdDays && remainingDays > 0
        } yield ThresholdReached(schedule.id, "days", thresholdDays.toString, remainingDays.toString)

        val hoursReached = for {
          thresholdHours <- threshold.thresholdHours
          remainingHours <- schedule.remainingEngineHours
          if remainingHours <= thresholdHours && remainingHours > 0
        } yield ThresholdReached(schedule.id, "hours", thresholdHours.toString, remainingHours.toString)

        List(kmReached, daysReached, hoursReached).flatten
      }
    }

object IntervalCalculatorLive:
  val live: ZLayer[Any, Nothing, IntervalCalculator] =
    ZLayer.succeed(IntervalCalculatorLive())
