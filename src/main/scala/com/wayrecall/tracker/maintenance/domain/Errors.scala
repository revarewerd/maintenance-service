package com.wayrecall.tracker.maintenance.domain

import java.util.UUID

// ============================================================
// DOMAIN ERRORS — Maintenance Service
// Типизированные ошибки для exhaustive pattern matching
// ============================================================

sealed trait MaintenanceError:
  def message: String

object MaintenanceError:

  // ---- Шаблоны ----
  case class TemplateNotFound(id: UUID) extends MaintenanceError:
    def message = s"Шаблон ТО не найден: id=$id"

  case class TemplateAlreadyExists(name: String, companyId: UUID) extends MaintenanceError:
    def message = s"Шаблон с именем '$name' уже существует для компании $companyId"

  // ---- Расписания ----
  case class ScheduleNotFound(id: UUID) extends MaintenanceError:
    def message = s"Расписание ТО не найдено: id=$id"

  case class ScheduleAlreadyExists(vehicleId: UUID, templateId: UUID) extends MaintenanceError:
    def message = s"Расписание для машины $vehicleId по шаблону $templateId уже существует"

  // ---- Транспорт ----
  case class VehicleNotFound(id: UUID) extends MaintenanceError:
    def message = s"Транспорт не найден: id=$id"

  // ---- Валидация ----
  case class InvalidInterval(reason: String) extends MaintenanceError:
    def message = s"Невалидный интервал ТО: $reason"

  case class InvalidRequest(reason: String) extends MaintenanceError:
    def message = s"Невалидный запрос: $reason"

  // ---- Напоминания ----
  case class ReminderAlreadySent(scheduleId: UUID, threshold: String) extends MaintenanceError:
    def message = s"Напоминание уже отправлено: schedule=$scheduleId, порог=$threshold"

  // ---- Инфраструктура ----
  case class DatabaseError(cause: Throwable) extends MaintenanceError:
    def message = s"Ошибка БД: ${cause.getMessage}"

  case class CacheError(cause: Throwable) extends MaintenanceError:
    def message = s"Ошибка кэша: ${cause.getMessage}"

  case class KafkaError(cause: Throwable) extends MaintenanceError:
    def message = s"Ошибка Kafka: ${cause.getMessage}"

  case class LockAcquireError(key: String) extends MaintenanceError:
    def message = s"Не удалось захватить блокировку: $key"

  // ---- Записи ТО ----
  case class ServiceRecordNotFound(id: UUID) extends MaintenanceError:
    def message = s"Запись о ТО не найдена: id=$id"
