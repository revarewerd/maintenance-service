package com.wayrecall.tracker.maintenance

import com.wayrecall.tracker.maintenance.api.*
import com.wayrecall.tracker.maintenance.cache.*
import com.wayrecall.tracker.maintenance.config.AppConfig
import com.wayrecall.tracker.maintenance.infrastructure.TransactorLayer
import com.wayrecall.tracker.maintenance.kafka.*
import com.wayrecall.tracker.maintenance.repository.*
import com.wayrecall.tracker.maintenance.scheduler.*
import com.wayrecall.tracker.maintenance.service.*
import zio.*
import zio.http.*
import zio.kafka.consumer.{Consumer, ConsumerSettings}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.logging.backend.SLF4J

// ============================================================
// Main — точка входа Maintenance Service (порт 8087)
// Управление техническим обслуживанием транспорта
// ============================================================

object Main extends ZIOAppDefault:

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override def run: ZIO[Any, Any, Any] =
    val program = for {
      config <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo(s"=== Maintenance Service запускается на порту ${config.server.port} ===")

      // Запускаем планировщик задач
      jobs   <- ZIO.service[MaintenanceJobs]
      _      <- jobs.startAll.catchAll(err => ZIO.logError(s"Ошибка запуска планировщика: $err"))

      // Запускаем Kafka consumer для пробега (daemon — в фоне)
      _      <- MileageConsumer.run.forkDaemon
                  .catchAll(err => ZIO.logError(s"Ошибка запуска Kafka consumer: $err"))

      // Собираем маршруты
      allRoutes = HealthRoutes.routes ++ MaintenanceRoutes.routes

      // Запускаем HTTP-сервер
      _      <- Server.serve(allRoutes)
    } yield ()

    program.provide(
      // Конфигурация
      AppConfig.live,

      // Извлечение sub-конфигов
      ZLayer.service[AppConfig].flatMap(env => ZLayer.succeed(env.get.postgres)),
      ZLayer.service[AppConfig].flatMap(env => ZLayer.succeed(env.get.redis)),
      ZLayer.service[AppConfig].flatMap(env => ZLayer.succeed(env.get.kafka)),
      ZLayer.service[AppConfig].flatMap(env => ZLayer.succeed(env.get.scheduler)),
      ZLayer.service[AppConfig].flatMap(env => ZLayer.succeed(env.get.reminderThresholds)),

      // БД
      TransactorLayer.live,

      // Redis
      zio.redis.Redis.local,
      zio.redis.RedisExecutor.local,
      zio.redis.CodecSupplier.utf8,

      // Kafka Consumer
      ZLayer.service[AppConfig].flatMap { env =>
        val kafkaConfig = env.get.kafka
        Consumer.make(
          ConsumerSettings(List(kafkaConfig.bootstrapServers))
            .withGroupId(kafkaConfig.consumerGroup)
        ).toLayer
      },

      // Kafka Producer
      ZLayer.service[AppConfig].flatMap { env =>
        val kafkaConfig = env.get.kafka
        Producer.make(
          ProducerSettings(List(kafkaConfig.bootstrapServers))
        ).toLayer
      },

      // Репозитории
      TemplateRepositoryLive.live,
      ScheduleRepositoryLive.live,
      ServiceRecordRepositoryLive.live,
      OdometerRepositoryLive.live,

      // Кэш
      MaintenanceCacheLive.live,

      // Kafka продюсер
      MaintenanceEventProducerLive.live,

      // Сервисы (порядок важен из-за зависимостей)
      IntervalCalculatorLive.live,
      ReminderEngineLive.live,
      MileageTrackerLive.live,
      MaintenancePlannerLive.live,
      MaintenanceServiceLive.live,

      // Планировщик
      MaintenanceJobsLive.live,

      // HTTP-сервер
      Server.defaultWithPort(8087)
    )
