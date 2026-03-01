package com.wayrecall.tracker.maintenance.domain

import zio.json.*
import java.time.{Instant, LocalDate}
import java.util.UUID

// ============================================================
// DOMAIN MODELS — Maintenance Service
// Модели технического обслуживания: шаблоны, расписания,
// записи ТО, одометр, пробег, напоминания
// ============================================================

// ---- Перечисления ----

/** Тип интервала ТО */
enum IntervalType derives JsonCodec:
  case Mileage       // По пробегу (км)
  case EngineHours   // По моточасам
  case Calendar      // По календарю (дни)
  case Combined      // Комбинированный (что наступит раньше)

/** Приоритет обслуживания */
enum ServicePriority derives JsonCodec:
  case Critical  // Критическое — немедленно
  case High      // Высокий — в течение недели
  case Normal    // Обычный — плановое ТО
  case Low       // Низкий — при возможности

/** Статус расписания ТО */
enum ScheduleStatus derives JsonCodec:
  case Active    // Активно — отслеживается
  case Paused    // Приостановлено
  case Overdue   // Просрочено
  case Completed // Завершено (однократное ТО)

/** Тип записи о ТО */
enum ServiceType derives JsonCodec:
  case Scheduled    // Плановое
  case Unscheduled  // Внеплановое
  case Emergency    // Аварийное
  case Inspection   // Осмотр

// ---- Шаблоны ТО ----

/** Шаблон технического обслуживания */
final case class MaintenanceTemplate(
  id: UUID,
  companyId: UUID,
  name: String,
  description: Option[String],
  vehicleType: Option[String],          // Тип ТС (если шаблон для конкретного типа)
  intervalType: IntervalType,
  intervalMileageKm: Option[Long],      // Интервал в км (для Mileage/Combined)
  intervalEngineHours: Option[Int],     // Интервал в моточасах (для EngineHours/Combined)
  intervalDays: Option[Int],            // Интервал в днях (для Calendar/Combined)
  priority: ServicePriority,
  estimatedDurationMinutes: Option[Int],
  estimatedCostRub: Option[BigDecimal],
  items: List[MaintenanceItem],         // Работы в рамках ТО
  reminders: List[ReminderConfig],      // Настройки напоминаний
  isActive: Boolean,
  createdAt: Instant,
  updatedAt: Instant
) derives JsonCodec

/** Отдельная работа в шаблоне ТО */
final case class MaintenanceItem(
  id: UUID,
  templateId: UUID,
  name: String,
  description: Option[String],
  partNumber: Option[String],           // Артикул запчасти
  estimatedCostRub: Option[BigDecimal],
  sortOrder: Int
) derives JsonCodec

/** Настройка напоминания */
final case class ReminderConfig(
  thresholdKm: Option[Long],            // За сколько км напомнить
  thresholdDays: Option[Int],           // За сколько дней напомнить
  thresholdHours: Option[Int]           // За сколько моточасов напомнить
) derives JsonCodec

// ---- Расписания ТО ----

/** Расписание ТО для конкретного транспорта */
final case class MaintenanceSchedule(
  id: UUID,
  companyId: UUID,
  vehicleId: UUID,
  templateId: UUID,
  templateName: String,
  intervalType: IntervalType,
  intervalMileageKm: Option[Long],
  intervalEngineHours: Option[Int],
  intervalDays: Option[Int],
  priority: ServicePriority,
  status: ScheduleStatus,
  // Текущие значения
  currentMileageKm: Long,
  currentEngineHours: Int,
  // Последнее ТО
  lastServiceDate: Option[LocalDate],
  lastServiceMileageKm: Option[Long],
  lastServiceEngineHours: Option[Int],
  // Следующее ТО (рассчитывается автоматически)
  nextServiceMileageKm: Option[Long],
  nextServiceEngineHours: Option[Int],
  nextServiceDate: Option[LocalDate],
  // Оставшееся до ТО
  remainingKm: Option[Long],
  remainingEngineHours: Option[Int],
  remainingDays: Option[Int],
  // Флаги отправленных напоминаний (идемпотентность)
  reminderFirstSent: Boolean,
  reminderSecondSent: Boolean,
  reminderThirdSent: Boolean,
  reminderOverdueSent: Boolean,
  createdAt: Instant,
  updatedAt: Instant
) derives JsonCodec

// ---- Записи о ТО ----

/** Запись о выполненном техническом обслуживании */
final case class ServiceRecord(
  id: UUID,
  companyId: UUID,
  vehicleId: UUID,
  scheduleId: Option[UUID],            // Привязка к расписанию (может быть внеплановым)
  serviceType: ServiceType,
  description: String,
  mileageKm: Long,                     // Пробег на момент ТО
  engineHours: Option[Int],
  serviceDate: LocalDate,
  performedBy: Option[String],          // Кто выполнил
  workshop: Option[String],            // Где выполняли
  totalCostRub: Option[BigDecimal],
  items: List[ServiceItemRecord],
  notes: Option[String],
  attachments: List[String],           // URL-ы документов/фото
  nextServiceMileageKm: Option[Long],  // Ручная установка следующего ТО
  nextServiceDate: Option[LocalDate],
  createdBy: UUID,                     // ID пользователя
  createdAt: Instant,
  updatedAt: Instant
) derives JsonCodec

/** Запись о конкретной работе при ТО */
final case class ServiceItemRecord(
  id: UUID,
  serviceRecordId: UUID,
  name: String,
  partNumber: Option[String],
  quantity: Int,
  costRub: Option[BigDecimal],
  notes: Option[String]
) derives JsonCodec

// ---- Одометр и пробег ----

/** Показание одометра */
final case class OdometerReading(
  id: UUID,
  vehicleId: UUID,
  mileageKm: Long,
  source: String,                      // "gps", "manual", "obd"
  recordedAt: Instant,
  createdAt: Instant
) derives JsonCodec

/** Показание моточасов */
final case class EngineHoursReading(
  vehicleId: UUID,
  engineHours: Int,
  source: String,
  recordedAt: Instant
) derives JsonCodec

/** Суточный пробег */
final case class DailyMileage(
  vehicleId: UUID,
  date: LocalDate,
  startMileageKm: Long,
  endMileageKm: Long,
  distanceKm: Long,
  createdAt: Instant
) derives JsonCodec

// ---- События и напоминания ----

/** Событие ТО для Kafka (producer: maintenance-events) */
enum MaintenanceEvent:
  case ScheduleCreated(scheduleId: UUID, vehicleId: UUID, companyId: UUID, templateName: String)
  case ServiceDueReminder(scheduleId: UUID, vehicleId: UUID, companyId: UUID, remainingKm: Option[Long], remainingDays: Option[Int], priority: ServicePriority)
  case ServiceOverdue(scheduleId: UUID, vehicleId: UUID, companyId: UUID, overdueKm: Option[Long], overdueDays: Option[Int])
  case ServiceCompleted(recordId: UUID, vehicleId: UUID, companyId: UUID, mileageKm: Long, serviceDate: LocalDate)

object MaintenanceEvent:
  given JsonEncoder[MaintenanceEvent] = DeriveJsonEncoder.gen[MaintenanceEvent]
  given JsonDecoder[MaintenanceEvent] = DeriveJsonDecoder.gen[MaintenanceEvent]

/** Запись в журнале напоминаний */
final case class MaintenanceReminder(
  id: UUID,
  scheduleId: UUID,
  vehicleId: UUID,
  companyId: UUID,
  reminderType: String,                // "threshold_km_500", "threshold_days_7", "overdue"
  remainingKm: Option[Long],
  remainingDays: Option[Int],
  sentAt: Instant,
  acknowledged: Boolean
) derives JsonCodec

// ---- API модели (запросы/ответы) ----

/** Создание шаблона ТО */
final case class CreateTemplateRequest(
  name: String,
  description: Option[String],
  vehicleType: Option[String],
  intervalType: IntervalType,
  intervalMileageKm: Option[Long],
  intervalEngineHours: Option[Int],
  intervalDays: Option[Int],
  priority: ServicePriority,
  estimatedDurationMinutes: Option[Int],
  estimatedCostRub: Option[BigDecimal],
  items: List[CreateItemRequest],
  reminders: List[ReminderConfig]
) derives JsonCodec

final case class CreateItemRequest(
  name: String,
  description: Option[String],
  partNumber: Option[String],
  estimatedCostRub: Option[BigDecimal],
  sortOrder: Int
) derives JsonCodec

/** Создание расписания ТО */
final case class CreateScheduleRequest(
  vehicleId: UUID,
  templateId: UUID,
  currentMileageKm: Long,
  currentEngineHours: Option[Int],
  lastServiceDate: Option[LocalDate],
  lastServiceMileageKm: Option[Long],
  lastServiceEngineHours: Option[Int]
) derives JsonCodec

/** Регистрация ТО */
final case class RecordServiceRequest(
  vehicleId: UUID,
  scheduleId: Option[UUID],
  serviceType: ServiceType,
  description: String,
  mileageKm: Long,
  engineHours: Option[Int],
  serviceDate: LocalDate,
  performedBy: Option[String],
  workshop: Option[String],
  totalCostRub: Option[BigDecimal],
  items: List[CreateServiceItemRequest],
  notes: Option[String],
  nextServiceMileageKm: Option[Long],
  nextServiceDate: Option[LocalDate]
) derives JsonCodec

final case class CreateServiceItemRequest(
  name: String,
  partNumber: Option[String],
  quantity: Int,
  costRub: Option[BigDecimal],
  notes: Option[String]
) derives JsonCodec

/** Обзор ТО по транспорту */
final case class VehicleMaintenanceOverview(
  vehicleId: UUID,
  schedules: List[ScheduleSummary],
  recentServices: List[ServiceSummary],
  currentMileageKm: Long,
  overdueCount: Int,
  upcomingCount: Int
) derives JsonCodec

/** Краткая информация о расписании */
final case class ScheduleSummary(
  id: UUID,
  templateName: String,
  status: ScheduleStatus,
  priority: ServicePriority,
  remainingKm: Option[Long],
  remainingDays: Option[Int],
  nextServiceDate: Option[LocalDate]
) derives JsonCodec

/** Краткая информация о ТО */
final case class ServiceSummary(
  id: UUID,
  description: String,
  serviceDate: LocalDate,
  mileageKm: Long,
  totalCostRub: Option[BigDecimal],
  serviceType: ServiceType
) derives JsonCodec

/** Обзор ТО по компании */
final case class CompanyMaintenanceOverview(
  companyId: UUID,
  totalSchedules: Int,
  activeSchedules: Int,
  overdueCount: Int,
  upcomingThisWeek: Int,
  totalCostThisMonth: BigDecimal,
  vehicleOverviews: List[VehicleMaintenanceOverview]
) derives JsonCodec

/** Страница истории ТО */
final case class ServiceHistoryPage(
  records: List[ServiceRecord],
  total: Int,
  limit: Int,
  offset: Int
) derives JsonCodec

/** Информация о следующем ТО (результат расчёта) */
final case class NextServiceInfo(
  nextMileageKm: Option[Long],
  nextEngineHours: Option[Int],
  nextDate: Option[LocalDate]
) derives JsonCodec

/** Оставшееся до ТО (результат расчёта) */
final case class RemainingInfo(
  remainingKm: Option[Long],
  remainingEngineHours: Option[Int],
  remainingDays: Option[Int]
) derives JsonCodec

/** Достигнутый порог напоминания */
final case class ThresholdReached(
  scheduleId: UUID,
  thresholdType: String,
  thresholdValue: String,
  currentValue: String
)

/** Информация об отправленном напоминании */
final case class ReminderSent(
  scheduleId: UUID,
  vehicleId: UUID,
  companyId: UUID,
  reminderType: String
)

/** Кэшированное расписание для Redis */
final case class CachedSchedule(
  id: UUID,
  templateName: String,
  intervalType: IntervalType,
  nextServiceMileageKm: Option[Long],
  nextServiceDate: Option[LocalDate],
  status: ScheduleStatus,
  priority: ServicePriority
) derives JsonCodec

/** GPS событие для извлечения пробега */
final case class GpsEvent(
  deviceId: Long,
  vehicleId: Option[Long],
  imei: String,
  latitude: Double,
  longitude: Double,
  speed: Int,
  odometer: Option[Long],              // Показание одометра (м)
  engineHours: Option[Int],            // Моточасы
  timestamp: Instant,
  organizationId: Long
) derives JsonCodec
