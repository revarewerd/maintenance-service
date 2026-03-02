package com.wayrecall.tracker.maintenance.repository

import cats.implicits.*
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
// SERVICE RECORD REPOSITORY — записи о выполненном ТО
// PostgreSQL: maintenance.service_records + service_record_items
// ============================================================

trait ServiceRecordRepository:
  def create(record: ServiceRecord): Task[UUID]
  def findById(id: UUID): Task[Option[ServiceRecord]]
  def findByVehicle(vehicleId: UUID, limit: Int, offset: Int): Task[List[ServiceRecord]]
  def countByVehicle(vehicleId: UUID): Task[Int]
  def findByCompany(companyId: UUID, limit: Int, offset: Int): Task[List[ServiceRecord]]
  def totalCostForPeriod(companyId: UUID, from: LocalDate, to: LocalDate): Task[BigDecimal]

object ServiceRecordRepository:
  def create(record: ServiceRecord): ZIO[ServiceRecordRepository, Throwable, UUID] =
    ZIO.serviceWithZIO[ServiceRecordRepository](_.create(record))
  def findById(id: UUID): ZIO[ServiceRecordRepository, Throwable, Option[ServiceRecord]] =
    ZIO.serviceWithZIO[ServiceRecordRepository](_.findById(id))

case class ServiceRecordRepositoryLive(xa: Transactor[Task]) extends ServiceRecordRepository:

  given Meta[ServiceType] = Meta[String].imap(ServiceType.valueOf)(_.toString)
  given Meta[BigDecimal]  = Meta[java.math.BigDecimal].imap(BigDecimal(_))(_.bigDecimal)

  override def create(record: ServiceRecord): Task[UUID] =
    val insertRecord =
      sql"""
        INSERT INTO maintenance.service_records (
          id, company_id, vehicle_id, schedule_id, service_type,
          description, mileage_km, engine_hours, service_date,
          performed_by, workshop, total_cost_rub, notes, attachments,
          next_service_mileage_km, next_service_date,
          created_by, created_at, updated_at
        ) VALUES (
          ${record.id}, ${record.companyId}, ${record.vehicleId}, ${record.scheduleId},
          ${record.serviceType.toString}::maintenance.service_type,
          ${record.description}, ${record.mileageKm}, ${record.engineHours},
          ${record.serviceDate}, ${record.performedBy}, ${record.workshop},
          ${record.totalCostRub}, ${record.notes},
          ${record.attachments.mkString(",")},
          ${record.nextServiceMileageKm}, ${record.nextServiceDate},
          ${record.createdBy}, ${record.createdAt}, ${record.updatedAt}
        )
      """.update.run

    val insertItems = record.items.traverse_ { item =>
      sql"""
        INSERT INTO maintenance.service_record_items (id, service_record_id, name, part_number, quantity, cost_rub, notes)
        VALUES (${item.id}, ${item.serviceRecordId}, ${item.name}, ${item.partNumber}, ${item.quantity}, ${item.costRub}, ${item.notes})
      """.update.run
    }

    (insertRecord *> insertItems).transact(xa).as(record.id)

  override def findById(id: UUID): Task[Option[ServiceRecord]] =
    // Упрощённый запрос — items загружаем отдельно
    val recordQuery = sql"""
      SELECT id, company_id, vehicle_id, schedule_id, service_type,
             description, mileage_km, engine_hours, service_date,
             performed_by, workshop, total_cost_rub, notes, attachments,
             next_service_mileage_km, next_service_date,
             created_by, created_at, updated_at
      FROM maintenance.service_records WHERE id = $id
    """.query[(UUID, UUID, UUID, Option[UUID], String,
               String, Long, Option[Int], LocalDate,
               Option[String], Option[String], Option[BigDecimal], Option[String], Option[String],
               Option[Long], Option[LocalDate],
               UUID, Instant, Instant)].option

    val itemsQuery = sql"""
      SELECT id, service_record_id, name, part_number, quantity, cost_rub, notes
      FROM maintenance.service_record_items WHERE service_record_id = $id
    """.query[ServiceItemRecord].to[List]

    val tx = for {
      recOpt <- recordQuery
      items  <- itemsQuery
    } yield recOpt.map { case (id, companyId, vehicleId, scheduleId, sType,
                               desc, mileage, hours, sDate,
                               performedBy, workshop, cost, notes, attachStr,
                               nextMileage, nextDate, createdBy, createdAt, updatedAt) =>
      ServiceRecord(
        id, companyId, vehicleId, scheduleId,
        ServiceType.valueOf(sType), desc, mileage, hours, sDate,
        performedBy, workshop, cost, items, notes,
        attachStr.map(_.split(",").toList).getOrElse(Nil),
        nextMileage, nextDate, createdBy, createdAt, updatedAt
      )
    }

    tx.transact(xa)

  override def findByVehicle(vehicleId: UUID, limit: Int, offset: Int): Task[List[ServiceRecord]] =
    sql"""
      SELECT id FROM maintenance.service_records
      WHERE vehicle_id = $vehicleId
      ORDER BY service_date DESC LIMIT $limit OFFSET $offset
    """.query[UUID].to[List].transact(xa).flatMap { ids =>
      ZIO.foreach(ids)(id => findById(id).map(_.get))
    }

  override def countByVehicle(vehicleId: UUID): Task[Int] =
    sql"SELECT COUNT(*)::int FROM maintenance.service_records WHERE vehicle_id = $vehicleId"
      .query[Int].unique.transact(xa)

  override def findByCompany(companyId: UUID, limit: Int, offset: Int): Task[List[ServiceRecord]] =
    sql"""
      SELECT id FROM maintenance.service_records
      WHERE company_id = $companyId
      ORDER BY service_date DESC LIMIT $limit OFFSET $offset
    """.query[UUID].to[List].transact(xa).flatMap { ids =>
      ZIO.foreach(ids)(id => findById(id).map(_.get))
    }

  override def totalCostForPeriod(companyId: UUID, from: LocalDate, to: LocalDate): Task[BigDecimal] =
    sql"""
      SELECT COALESCE(SUM(total_cost_rub), 0)
      FROM maintenance.service_records
      WHERE company_id = $companyId AND service_date BETWEEN $from AND $to
    """.query[BigDecimal].unique.transact(xa)

object ServiceRecordRepositoryLive:
  val live: ZLayer[Transactor[Task], Nothing, ServiceRecordRepository] =
    ZLayer.fromFunction(ServiceRecordRepositoryLive(_))
