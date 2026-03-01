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
// ODOMETER REPOSITORY — показания одометра и суточный пробег
// PostgreSQL: maintenance.odometer_readings, maintenance.daily_mileage
// ============================================================

trait OdometerRepository:
  def saveReading(reading: OdometerReading): Task[Unit]
  def getLatest(vehicleId: UUID): Task[Option[OdometerReading]]
  def getReadings(vehicleId: UUID, from: Instant, to: Instant): Task[List[OdometerReading]]
  def saveDailyMileage(mileage: DailyMileage): Task[Unit]
  def getDailyMileage(vehicleId: UUID, date: LocalDate): Task[Option[DailyMileage]]
  def getDailyMileageRange(vehicleId: UUID, from: LocalDate, to: LocalDate): Task[List[DailyMileage]]
  def saveEngineHours(reading: EngineHoursReading): Task[Unit]
  def getLatestEngineHours(vehicleId: UUID): Task[Option[EngineHoursReading]]

object OdometerRepository:
  def saveReading(reading: OdometerReading): ZIO[OdometerRepository, Throwable, Unit] =
    ZIO.serviceWithZIO[OdometerRepository](_.saveReading(reading))
  def getLatest(vehicleId: UUID): ZIO[OdometerRepository, Throwable, Option[OdometerReading]] =
    ZIO.serviceWithZIO[OdometerRepository](_.getLatest(vehicleId))

case class OdometerRepositoryLive(xa: Transactor[Task]) extends OdometerRepository:

  override def saveReading(reading: OdometerReading): Task[Unit] =
    sql"""
      INSERT INTO maintenance.odometer_readings (id, vehicle_id, mileage_km, source, recorded_at, created_at)
      VALUES (${reading.id}, ${reading.vehicleId}, ${reading.mileageKm}, ${reading.source}, ${reading.recordedAt}, ${reading.createdAt})
      ON CONFLICT DO NOTHING
    """.update.run.transact(xa).unit

  override def getLatest(vehicleId: UUID): Task[Option[OdometerReading]] =
    sql"""
      SELECT id, vehicle_id, mileage_km, source, recorded_at, created_at
      FROM maintenance.odometer_readings
      WHERE vehicle_id = $vehicleId ORDER BY recorded_at DESC LIMIT 1
    """.query[OdometerReading].option.transact(xa)

  override def getReadings(vehicleId: UUID, from: Instant, to: Instant): Task[List[OdometerReading]] =
    sql"""
      SELECT id, vehicle_id, mileage_km, source, recorded_at, created_at
      FROM maintenance.odometer_readings
      WHERE vehicle_id = $vehicleId AND recorded_at BETWEEN $from AND $to
      ORDER BY recorded_at
    """.query[OdometerReading].to[List].transact(xa)

  override def saveDailyMileage(mileage: DailyMileage): Task[Unit] =
    sql"""
      INSERT INTO maintenance.daily_mileage (vehicle_id, date, start_mileage_km, end_mileage_km, distance_km, created_at)
      VALUES (${mileage.vehicleId}, ${mileage.date}, ${mileage.startMileageKm}, ${mileage.endMileageKm}, ${mileage.distanceKm}, ${mileage.createdAt})
      ON CONFLICT (vehicle_id, date) DO UPDATE SET
        end_mileage_km = EXCLUDED.end_mileage_km,
        distance_km = EXCLUDED.distance_km
    """.update.run.transact(xa).unit

  override def getDailyMileage(vehicleId: UUID, date: LocalDate): Task[Option[DailyMileage]] =
    sql"""
      SELECT vehicle_id, date, start_mileage_km, end_mileage_km, distance_km, created_at
      FROM maintenance.daily_mileage WHERE vehicle_id = $vehicleId AND date = $date
    """.query[DailyMileage].option.transact(xa)

  override def getDailyMileageRange(vehicleId: UUID, from: LocalDate, to: LocalDate): Task[List[DailyMileage]] =
    sql"""
      SELECT vehicle_id, date, start_mileage_km, end_mileage_km, distance_km, created_at
      FROM maintenance.daily_mileage
      WHERE vehicle_id = $vehicleId AND date BETWEEN $from AND $to
      ORDER BY date
    """.query[DailyMileage].to[List].transact(xa)

  override def saveEngineHours(reading: EngineHoursReading): Task[Unit] =
    sql"""
      INSERT INTO maintenance.engine_hours_readings (vehicle_id, engine_hours, source, recorded_at)
      VALUES (${reading.vehicleId}, ${reading.engineHours}, ${reading.source}, ${reading.recordedAt})
    """.update.run.transact(xa).unit

  override def getLatestEngineHours(vehicleId: UUID): Task[Option[EngineHoursReading]] =
    sql"""
      SELECT vehicle_id, engine_hours, source, recorded_at
      FROM maintenance.engine_hours_readings
      WHERE vehicle_id = $vehicleId ORDER BY recorded_at DESC LIMIT 1
    """.query[EngineHoursReading].option.transact(xa)

object OdometerRepositoryLive:
  val live: ZLayer[Transactor[Task], Nothing, OdometerRepository] =
    ZLayer.fromFunction(OdometerRepositoryLive(_))
