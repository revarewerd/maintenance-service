package com.wayrecall.tracker.maintenance.repository

import zio.*
import zio.interop.catz.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javasql.*
import doobie.postgres.implicits.*
import com.wayrecall.tracker.maintenance.domain.*
import java.util.UUID
import java.time.{Instant, LocalDate}

// ============================================================
// SCHEDULE REPOSITORY — расписания ТО
// PostgreSQL: maintenance.schedules
// ============================================================

trait ScheduleRepository:
  def create(schedule: MaintenanceSchedule): Task[UUID]
  def findById(id: UUID): Task[Option[MaintenanceSchedule]]
  def findByVehicle(vehicleId: UUID): Task[List[MaintenanceSchedule]]
  def findActiveByCompany(companyId: UUID): Task[List[MaintenanceSchedule]]
  def findOverdue(): Task[List[MaintenanceSchedule]]
  def findApproachingThreshold(thresholdKm: Long, thresholdDays: Int): Task[List[MaintenanceSchedule]]
  def updateMileage(vehicleId: UUID, mileageKm: Long, remainingKm: Option[Long]): Task[Unit]
  def updateEngineHours(vehicleId: UUID, hours: Int, remainingHours: Option[Int]): Task[Unit]
  def updateReminderFlag(scheduleId: UUID, flagName: String): Task[Unit]
  def updateStatus(scheduleId: UUID, status: ScheduleStatus): Task[Unit]
  def updateAfterService(scheduleId: UUID, nextMileage: Option[Long], nextDate: Option[LocalDate], nextHours: Option[Int]): Task[Unit]
  def resetReminderFlags(scheduleId: UUID): Task[Unit]

object ScheduleRepository:
  def create(schedule: MaintenanceSchedule): ZIO[ScheduleRepository, Throwable, UUID] =
    ZIO.serviceWithZIO[ScheduleRepository](_.create(schedule))
  def findById(id: UUID): ZIO[ScheduleRepository, Throwable, Option[MaintenanceSchedule]] =
    ZIO.serviceWithZIO[ScheduleRepository](_.findById(id))
  def findByVehicle(vehicleId: UUID): ZIO[ScheduleRepository, Throwable, List[MaintenanceSchedule]] =
    ZIO.serviceWithZIO[ScheduleRepository](_.findByVehicle(vehicleId))

case class ScheduleRepositoryLive(xa: Transactor[Task]) extends ScheduleRepository:

  given Meta[IntervalType]    = Meta[String].imap(IntervalType.valueOf)(_.toString)
  given Meta[ServicePriority] = Meta[String].imap(ServicePriority.valueOf)(_.toString)
  given Meta[ScheduleStatus]  = Meta[String].imap(ScheduleStatus.valueOf)(_.toString)
  given Meta[BigDecimal]      = Meta[java.math.BigDecimal].imap(BigDecimal(_))(_.bigDecimal)

  override def create(schedule: MaintenanceSchedule): Task[UUID] =
    sql"""
      INSERT INTO maintenance.schedules (
        id, company_id, vehicle_id, template_id, template_name,
        interval_type, interval_mileage_km, interval_engine_hours, interval_days,
        priority, status, current_mileage_km, current_engine_hours,
        last_service_date, last_service_mileage_km, last_service_engine_hours,
        next_service_mileage_km, next_service_engine_hours, next_service_date,
        remaining_km, remaining_engine_hours, remaining_days,
        reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
        created_at, updated_at
      ) VALUES (
        ${schedule.id}, ${schedule.companyId}, ${schedule.vehicleId},
        ${schedule.templateId}, ${schedule.templateName},
        ${schedule.intervalType.toString}::maintenance.interval_type,
        ${schedule.intervalMileageKm}, ${schedule.intervalEngineHours}, ${schedule.intervalDays},
        ${schedule.priority.toString}::maintenance.service_priority,
        ${schedule.status.toString}::maintenance.schedule_status,
        ${schedule.currentMileageKm}, ${schedule.currentEngineHours},
        ${schedule.lastServiceDate}, ${schedule.lastServiceMileageKm}, ${schedule.lastServiceEngineHours},
        ${schedule.nextServiceMileageKm}, ${schedule.nextServiceEngineHours}, ${schedule.nextServiceDate},
        ${schedule.remainingKm}, ${schedule.remainingEngineHours}, ${schedule.remainingDays},
        false, false, false, false,
        ${schedule.createdAt}, ${schedule.updatedAt}
      )
    """.update.run.transact(xa).as(schedule.id)

  override def findById(id: UUID): Task[Option[MaintenanceSchedule]] =
    sql"""
      SELECT id, company_id, vehicle_id, template_id, template_name,
             interval_type, interval_mileage_km, interval_engine_hours, interval_days,
             priority, status, current_mileage_km, current_engine_hours,
             last_service_date, last_service_mileage_km, last_service_engine_hours,
             next_service_mileage_km, next_service_engine_hours, next_service_date,
             remaining_km, remaining_engine_hours, remaining_days,
             reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
             created_at, updated_at
      FROM maintenance.schedules WHERE id = $id
    """.query[MaintenanceSchedule].option.transact(xa)

  override def findByVehicle(vehicleId: UUID): Task[List[MaintenanceSchedule]] =
    sql"""
      SELECT id, company_id, vehicle_id, template_id, template_name,
             interval_type, interval_mileage_km, interval_engine_hours, interval_days,
             priority, status, current_mileage_km, current_engine_hours,
             last_service_date, last_service_mileage_km, last_service_engine_hours,
             next_service_mileage_km, next_service_engine_hours, next_service_date,
             remaining_km, remaining_engine_hours, remaining_days,
             reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
             created_at, updated_at
      FROM maintenance.schedules WHERE vehicle_id = $vehicleId
      ORDER BY priority ASC, next_service_date ASC NULLS LAST
    """.query[MaintenanceSchedule].to[List].transact(xa)

  override def findActiveByCompany(companyId: UUID): Task[List[MaintenanceSchedule]] =
    sql"""
      SELECT id, company_id, vehicle_id, template_id, template_name,
             interval_type, interval_mileage_km, interval_engine_hours, interval_days,
             priority, status, current_mileage_km, current_engine_hours,
             last_service_date, last_service_mileage_km, last_service_engine_hours,
             next_service_mileage_km, next_service_engine_hours, next_service_date,
             remaining_km, remaining_engine_hours, remaining_days,
             reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
             created_at, updated_at
      FROM maintenance.schedules WHERE company_id = $companyId AND status IN ('Active', 'Overdue')
      ORDER BY status DESC, priority ASC
    """.query[MaintenanceSchedule].to[List].transact(xa)

  override def findOverdue(): Task[List[MaintenanceSchedule]] =
    sql"""
      SELECT id, company_id, vehicle_id, template_id, template_name,
             interval_type, interval_mileage_km, interval_engine_hours, interval_days,
             priority, status, current_mileage_km, current_engine_hours,
             last_service_date, last_service_mileage_km, last_service_engine_hours,
             next_service_mileage_km, next_service_engine_hours, next_service_date,
             remaining_km, remaining_engine_hours, remaining_days,
             reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
             created_at, updated_at
      FROM maintenance.schedules WHERE status = 'Active'
        AND (
          (remaining_km IS NOT NULL AND remaining_km <= 0) OR
          (remaining_days IS NOT NULL AND remaining_days <= 0) OR
          (remaining_engine_hours IS NOT NULL AND remaining_engine_hours <= 0)
        )
    """.query[MaintenanceSchedule].to[List].transact(xa)

  override def findApproachingThreshold(thresholdKm: Long, thresholdDays: Int): Task[List[MaintenanceSchedule]] =
    sql"""
      SELECT id, company_id, vehicle_id, template_id, template_name,
             interval_type, interval_mileage_km, interval_engine_hours, interval_days,
             priority, status, current_mileage_km, current_engine_hours,
             last_service_date, last_service_mileage_km, last_service_engine_hours,
             next_service_mileage_km, next_service_engine_hours, next_service_date,
             remaining_km, remaining_engine_hours, remaining_days,
             reminder_first_sent, reminder_second_sent, reminder_third_sent, reminder_overdue_sent,
             created_at, updated_at
      FROM maintenance.schedules WHERE status = 'Active'
        AND (
          (remaining_km IS NOT NULL AND remaining_km <= $thresholdKm AND remaining_km > 0) OR
          (remaining_days IS NOT NULL AND remaining_days <= $thresholdDays AND remaining_days > 0)
        )
    """.query[MaintenanceSchedule].to[List].transact(xa)

  override def updateMileage(vehicleId: UUID, mileageKm: Long, remainingKm: Option[Long]): Task[Unit] =
    sql"""
      UPDATE maintenance.schedules SET
        current_mileage_km = $mileageKm,
        remaining_km = $remainingKm,
        updated_at = ${Instant.now()}
      WHERE vehicle_id = $vehicleId AND status IN ('Active', 'Overdue')
        AND interval_type IN ('Mileage', 'Combined')
    """.update.run.transact(xa).unit

  override def updateEngineHours(vehicleId: UUID, hours: Int, remainingHours: Option[Int]): Task[Unit] =
    sql"""
      UPDATE maintenance.schedules SET
        current_engine_hours = $hours,
        remaining_engine_hours = $remainingHours,
        updated_at = ${Instant.now()}
      WHERE vehicle_id = $vehicleId AND status IN ('Active', 'Overdue')
        AND interval_type IN ('EngineHours', 'Combined')
    """.update.run.transact(xa).unit

  override def updateReminderFlag(scheduleId: UUID, flagName: String): Task[Unit] =
    val column = flagName match
      case "first"   => Fragment.const("reminder_first_sent")
      case "second"  => Fragment.const("reminder_second_sent")
      case "third"   => Fragment.const("reminder_third_sent")
      case "overdue" => Fragment.const("reminder_overdue_sent")
      case _         => Fragment.const("reminder_first_sent")
    (fr"UPDATE maintenance.schedules SET " ++ column ++ fr" = true, updated_at = ${Instant.now()} WHERE id = $scheduleId")
      .update.run.transact(xa).unit

  override def updateStatus(scheduleId: UUID, status: ScheduleStatus): Task[Unit] =
    sql"""
      UPDATE maintenance.schedules SET
        status = ${status.toString}::maintenance.schedule_status,
        updated_at = ${Instant.now()}
      WHERE id = $scheduleId
    """.update.run.transact(xa).unit

  override def updateAfterService(scheduleId: UUID, nextMileage: Option[Long], nextDate: Option[LocalDate], nextHours: Option[Int]): Task[Unit] =
    sql"""
      UPDATE maintenance.schedules SET
        next_service_mileage_km = $nextMileage,
        next_service_date = $nextDate,
        next_service_engine_hours = $nextHours,
        last_service_date = ${LocalDate.now()},
        last_service_mileage_km = current_mileage_km,
        last_service_engine_hours = current_engine_hours,
        status = 'Active'::maintenance.schedule_status,
        reminder_first_sent = false,
        reminder_second_sent = false,
        reminder_third_sent = false,
        reminder_overdue_sent = false,
        updated_at = ${Instant.now()}
      WHERE id = $scheduleId
    """.update.run.transact(xa).unit

  override def resetReminderFlags(scheduleId: UUID): Task[Unit] =
    sql"""
      UPDATE maintenance.schedules SET
        reminder_first_sent = false, reminder_second_sent = false,
        reminder_third_sent = false, reminder_overdue_sent = false,
        updated_at = ${Instant.now()}
      WHERE id = $scheduleId
    """.update.run.transact(xa).unit

object ScheduleRepositoryLive:
  val live: ZLayer[Transactor[Task], Nothing, ScheduleRepository] =
    ZLayer.fromFunction(ScheduleRepositoryLive(_))
