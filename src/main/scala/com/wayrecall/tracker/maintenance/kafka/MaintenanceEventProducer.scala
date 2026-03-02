package com.wayrecall.tracker.maintenance.kafka

import zio.*
import zio.kafka.producer.*
import zio.kafka.serde.Serde
import zio.json.*
import com.wayrecall.tracker.maintenance.domain.*
import com.wayrecall.tracker.maintenance.config.KafkaConfig

// ============================================================
// MAINTENANCE EVENT PRODUCER — продюсер событий ТО
//
// Topic: maintenance-events
// Partition key: vehicleId (из события)
//
// Типы событий:
//   - ScheduleCreated     — создано расписание ТО
//   - ServiceDueReminder  — напоминание о предстоящем ТО
//   - ServiceOverdue      — ТО просрочено
//   - ServiceCompleted    — ТО выполнено
// ============================================================

trait MaintenanceEventProducer:
  def publish(event: MaintenanceEvent): Task[Unit]

case class MaintenanceEventProducerLive(
  producer: Producer,
  config: KafkaConfig
) extends MaintenanceEventProducer:

  override def publish(event: MaintenanceEvent): Task[Unit] =
    val (key, json) = event match
      case e: MaintenanceEvent.ScheduleCreated    => (e.vehicleId.toString, event.toJson)
      case e: MaintenanceEvent.ServiceDueReminder => (e.vehicleId.toString, event.toJson)
      case e: MaintenanceEvent.ServiceOverdue     => (e.vehicleId.toString, event.toJson)
      case e: MaintenanceEvent.ServiceCompleted   => (e.vehicleId.toString, event.toJson)

    val record = new org.apache.kafka.clients.producer.ProducerRecord(
      config.maintenanceEventsTopic,
      key,
      json
    )

    producer.produce(record, Serde.string, Serde.string).unit
      .tap(_ => ZIO.logDebug(s"Опубликовано событие ТО: ${event.getClass.getSimpleName}, key=$key"))
      .catchAll(err => ZIO.logError(s"Ошибка публикации в Kafka: $err"))

object MaintenanceEventProducerLive:
  val live: ZLayer[Producer & KafkaConfig, Nothing, MaintenanceEventProducer] =
    ZLayer.fromFunction(MaintenanceEventProducerLive(_, _))
