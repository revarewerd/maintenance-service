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

  /**
   * Слой конфигурации с производными sub-конфигами
   */
  private val configLayers =
    AppConfig.live.flatMap { env =>
      val config = env.get
      ZLayer.succeed(config) ++
      ZLayer.succeed(config.postgres) ++
      ZLayer.succeed(config.kafka) ++
      ZLayer.succeed(config.scheduler) ++
      ZLayer.succeed(config.reminderThresholds)
    }

  /**
   * Kafka Consumer layer
   */
  private val kafkaConsumerLayer: ZLayer[AppConfig, Throwable, Consumer] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[AppConfig]
        consumer <- Consumer.make(
          ConsumerSettings(List(config.kafka.bootstrapServers))
            .withGroupId(config.kafka.consumerGroup)
        )
      } yield consumer
    }

  /**
   * Kafka Producer layer
   */
  private val kafkaProducerLayer: ZLayer[AppConfig, Throwable, Producer] =
    ZLayer.scoped {
      for {
        config <- ZIO.service[AppConfig]
        producer <- Producer.make(
          ProducerSettings(List(config.kafka.bootstrapServers))
        )
      } yield producer
    }

  override def run: ZIO[ZIOAppArgs & Scope, Any, Any] =
    val program = for {
      config <- ZIO.service[AppConfig]
      _      <- ZIO.logInfo(s"=== Maintenance Service запускается на порту ${config.server.port} ===")

      // Запускаем планировщик задач
      jobs   <- ZIO.service[MaintenanceJobs]
      _      <- jobs.startAll.catchAll(err => ZIO.logError(s"Ошибка запуска планировщика: $err"))

      // Запускаем Kafka consumer для пробега (daemon — в фоне)
      _      <- MileageConsumer.run.forkDaemon

      // Собираем маршруты
      allRoutes = HealthRoutes.routes ++ MaintenanceRoutes.routes

      // Запускаем HTTP-сервер
      _      <- Server.serve(allRoutes.toHttpApp)
    } yield ()

    program.provide(
      // Конфигурация
      configLayers,

      // БД
      TransactorLayer.live,

      // Kafka
      kafkaConsumerLayer,
      kafkaProducerLayer,

      // Репозитории
      TemplateRepositoryLive.live,
      ScheduleRepositoryLive.live,
      ServiceRecordRepositoryLive.live,
      OdometerRepositoryLive.live,

      // Кэш (in-memory Ref)
      MaintenanceCacheLive.live,

      // Kafka продюсер (бизнес-логика)
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
