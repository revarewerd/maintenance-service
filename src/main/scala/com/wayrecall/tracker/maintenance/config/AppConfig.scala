package com.wayrecall.tracker.maintenance.config

import zio.*
import zio.config.*
import zio.config.magnolia.deriveConfig
import zio.config.typesafe.TypesafeConfigProvider

// ============================================================
// Конфигурация Maintenance Service (порт 8087)
// ============================================================

final case class PostgresConfig(
  url: String,
  user: String,
  password: String,
  maxPoolSize: Int
)

final case class RedisConfig(
  host: String,
  port: Int
)

final case class KafkaConfig(
  bootstrapServers: String,
  consumerGroup: String,
  gpsTopic: String,
  maintenanceEventsTopic: String
)

final case class SchedulerConfig(
  calendarRemindersCron: String,   // Ежедневная проверка календарных напоминаний
  overdueCheckCron: String,        // Ежечасная проверка просроченных
  dailyMileageCron: String,        // Расчёт суточного пробега
  cleanupCron: String              // Ежемесячная очистка
)

final case class ReminderThresholdsConfig(
  firstKm: Long,                   // Первое напоминание — за 500 км
  secondKm: Long,                  // Второе напоминание — за 100 км
  firstDays: Int,                  // Первое напоминание — за 7 дней
  secondDays: Int                  // Второе напоминание — за 1 день
)

final case class ServerConfig(
  port: Int
)

final case class AppConfig(
  postgres: PostgresConfig,
  redis: RedisConfig,
  kafka: KafkaConfig,
  scheduler: SchedulerConfig,
  reminderThresholds: ReminderThresholdsConfig,
  server: ServerConfig
)

object AppConfig:
  val live: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO(
      ZIO.config[AppConfig](
        deriveConfig[AppConfig].mapKey(toKebabCase).nested("maintenance-service")
      )
    )
