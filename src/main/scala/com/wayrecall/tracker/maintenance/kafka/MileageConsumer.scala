package com.wayrecall.tracker.maintenance.kafka

import zio.*
import zio.stream.*
import zio.kafka.consumer.*
import zio.kafka.serde.Serde
import zio.json.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.config.KafkaConfig
import com.wayrecall.tracker.maintenance.service.MileageTracker

// ============================================================
// MILEAGE CONSUMER — Kafka consumer для GPS-событий
//
// Topic:         gps-events
// Consumer group: maintenance-service-mileage
//
// Извлекает odometer и engine_hours из GPS пакетов,
// обновляет пробег и проверяет пороги напоминаний.
// ============================================================

object MileageConsumer:

  /** Запустить Kafka consumer для отслеживания пробега */
  def run: ZIO[KafkaConfig & MileageTracker & Consumer, Throwable, Unit] =
    for {
      config         <- ZIO.service[KafkaConfig]
      mileageTracker <- ZIO.service[MileageTracker]
      _              <- ZIO.logInfo(s"Запуск MileageConsumer: топик=${config.gpsTopic}, группа=${config.consumerGroup}")
      _              <- Consumer
                          .plainStream(Subscription.topics(config.gpsTopic), Serde.string, Serde.string)
                          .mapZIO { record =>
                            processRecord(record.value, mileageTracker)
                              .catchAll(err =>
                                ZIO.logError(s"Ошибка обработки GPS события для пробега: $err")
                              )
                              .as(record.offset)
                          }
                          .aggregateAsync(Consumer.offsetBatches)
                          .mapZIO(_.commit)
                          .runDrain
    } yield ()

  /** Парсинг JSON и обработка GPS события */
  private def processRecord(json: String, tracker: MileageTracker): Task[Unit] =
    json.fromJson[GpsEvent] match
      case Left(err) =>
        // Не все GPS события содержат одометр — логируем только при ошибке парсинга
        ZIO.logDebug(s"Пропуск GPS события (невалидный JSON): $err")
      case Right(event) =>
        // Обрабатываем только если есть данные одометра или моточасов
        if event.odometer.isDefined || event.engineHours.isDefined then
          tracker.processGpsEvent(event)
        else
          ZIO.unit
