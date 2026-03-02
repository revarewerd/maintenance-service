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
// TEMPLATE REPOSITORY — CRUD шаблонов ТО
// PostgreSQL: maintenance.templates + template_items + reminder_configs
// ============================================================

trait TemplateRepository:
  def create(template: MaintenanceTemplate): Task[UUID]
  def findById(id: UUID): Task[Option[MaintenanceTemplate]]
  def findByCompany(companyId: UUID, vehicleType: Option[String]): Task[List[MaintenanceTemplate]]
  def update(template: MaintenanceTemplate): Task[Unit]
  def delete(id: UUID): Task[Unit]
  def existsByName(companyId: UUID, name: String): Task[Boolean]

object TemplateRepository:
  // Accessor-методы
  def create(template: MaintenanceTemplate): ZIO[TemplateRepository, Throwable, UUID] =
    ZIO.serviceWithZIO[TemplateRepository](_.create(template))
  def findById(id: UUID): ZIO[TemplateRepository, Throwable, Option[MaintenanceTemplate]] =
    ZIO.serviceWithZIO[TemplateRepository](_.findById(id))
  def findByCompany(companyId: UUID, vehicleType: Option[String]): ZIO[TemplateRepository, Throwable, List[MaintenanceTemplate]] =
    ZIO.serviceWithZIO[TemplateRepository](_.findByCompany(companyId, vehicleType))

case class TemplateRepositoryLive(xa: Transactor[Task]) extends TemplateRepository:

  // Doobie Meta для enum (хранится как text в PostgreSQL)
  given Meta[IntervalType]    = Meta[String].imap(IntervalType.valueOf)(_.toString)
  given Meta[ServicePriority] = Meta[String].imap(ServicePriority.valueOf)(_.toString)
  given Meta[BigDecimal]      = Meta[java.math.BigDecimal].imap(BigDecimal(_))(_.bigDecimal)

  override def create(template: MaintenanceTemplate): Task[UUID] =
    val insertTemplate =
      sql"""
        INSERT INTO maintenance.templates (
          id, company_id, name, description, vehicle_type,
          interval_type, interval_mileage_km, interval_engine_hours, interval_days,
          priority, estimated_duration_minutes, estimated_cost_rub, is_active,
          created_at, updated_at
        ) VALUES (
          ${template.id}, ${template.companyId}, ${template.name}, ${template.description},
          ${template.vehicleType}, ${template.intervalType.toString}::maintenance.interval_type,
          ${template.intervalMileageKm}, ${template.intervalEngineHours}, ${template.intervalDays},
          ${template.priority.toString}::maintenance.service_priority,
          ${template.estimatedDurationMinutes}, ${template.estimatedCostRub},
          ${template.isActive}, ${template.createdAt}, ${template.updatedAt}
        )
      """.update.run

    val insertItems = template.items.traverse_ { item =>
      sql"""
        INSERT INTO maintenance.template_items (id, template_id, name, description, part_number, estimated_cost_rub, sort_order)
        VALUES (${item.id}, ${item.templateId}, ${item.name}, ${item.description}, ${item.partNumber}, ${item.estimatedCostRub}, ${item.sortOrder})
      """.update.run
    }

    val insertReminders = template.reminders.traverse_ { reminder =>
      sql"""
        INSERT INTO maintenance.reminder_configs (id, template_id, threshold_km, threshold_days, threshold_hours)
        VALUES (${UUID.randomUUID()}, ${template.id}, ${reminder.thresholdKm}, ${reminder.thresholdDays}, ${reminder.thresholdHours})
      """.update.run
    }

    val tx = for {
      _ <- insertTemplate
      _ <- insertItems
      _ <- insertReminders
    } yield template.id

    tx.transact(xa)

  override def findById(id: UUID): Task[Option[MaintenanceTemplate]] =
    val query =
      sql"""
        SELECT id, company_id, name, description, vehicle_type,
               interval_type, interval_mileage_km, interval_engine_hours, interval_days,
               priority, estimated_duration_minutes, estimated_cost_rub, is_active,
               created_at, updated_at
        FROM maintenance.templates WHERE id = $id
      """.query[(UUID, UUID, String, Option[String], Option[String],
                 String, Option[Long], Option[Int], Option[Int],
                 String, Option[Int], Option[BigDecimal], Boolean,
                 Instant, Instant)].option

    val itemsQuery =
      sql"SELECT id, template_id, name, description, part_number, estimated_cost_rub, sort_order FROM maintenance.template_items WHERE template_id = $id ORDER BY sort_order"
        .query[MaintenanceItem].to[List]

    val remindersQuery =
      sql"SELECT threshold_km, threshold_days, threshold_hours FROM maintenance.reminder_configs WHERE template_id = $id"
        .query[ReminderConfig].to[List]

    val tx = for {
      tplOpt   <- query
      items    <- itemsQuery
      reminders <- remindersQuery
    } yield tplOpt.map { case (id, companyId, name, desc, vType, iType, mileage, hours, days, prio, dur, cost, active, created, updated) =>
      MaintenanceTemplate(
        id, companyId, name, desc, vType,
        IntervalType.valueOf(iType), mileage, hours, days,
        ServicePriority.valueOf(prio), dur, cost,
        items, reminders, active, created, updated
      )
    }

    tx.transact(xa)

  override def findByCompany(companyId: UUID, vehicleType: Option[String]): Task[List[MaintenanceTemplate]] =
    val baseQuery = fr"SELECT id FROM maintenance.templates WHERE company_id = $companyId AND is_active = true"
    val typeFilter = vehicleType.fold(Fragment.empty)(vt => fr"AND vehicle_type = $vt")
    val query = (baseQuery ++ typeFilter).query[UUID].to[List]

    for {
      ids       <- query.transact(xa)
      templates <- ZIO.foreach(ids)(id => findById(id).map(_.get))
    } yield templates

  override def update(template: MaintenanceTemplate): Task[Unit] =
    sql"""
      UPDATE maintenance.templates SET
        name = ${template.name}, description = ${template.description},
        vehicle_type = ${template.vehicleType},
        interval_type = ${template.intervalType.toString}::maintenance.interval_type,
        interval_mileage_km = ${template.intervalMileageKm},
        interval_engine_hours = ${template.intervalEngineHours},
        interval_days = ${template.intervalDays},
        priority = ${template.priority.toString}::maintenance.service_priority,
        estimated_duration_minutes = ${template.estimatedDurationMinutes},
        estimated_cost_rub = ${template.estimatedCostRub},
        is_active = ${template.isActive},
        updated_at = ${Instant.now()}
      WHERE id = ${template.id}
    """.update.run.transact(xa).unit

  override def delete(id: UUID): Task[Unit] =
    val tx = for {
      _ <- sql"DELETE FROM maintenance.reminder_configs WHERE template_id = $id".update.run
      _ <- sql"DELETE FROM maintenance.template_items WHERE template_id = $id".update.run
      _ <- sql"DELETE FROM maintenance.templates WHERE id = $id".update.run
    } yield ()
    tx.transact(xa)

  override def existsByName(companyId: UUID, name: String): Task[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM maintenance.templates WHERE company_id = $companyId AND name = $name AND is_active = true)"
      .query[Boolean].unique.transact(xa)

object TemplateRepositoryLive:
  val live: ZLayer[Transactor[Task], Nothing, TemplateRepository] =
    ZLayer.fromFunction(TemplateRepositoryLive(_))
