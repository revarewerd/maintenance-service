# 🔧 Maintenance Service — Runbook

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Запуск

### SBT (разработка)
```bash
cd services/maintenance-service
sbt run
```

### Docker
```bash
docker build -t wayrecall/maintenance-service .
docker run -p 8087:8087 \
  -e DATABASE_URL=jdbc:postgresql://postgres:5432/tracker \
  -e DATABASE_USER=tracker \
  -e DATABASE_PASSWORD=secret \
  -e REDIS_HOST=redis \
  -e KAFKA_BROKERS=kafka:9092 \
  wayrecall/maintenance-service
```

### Docker Compose
```bash
docker-compose up maintenance-service
```

## Health Check

```bash
curl http://localhost:8087/health
# Ожидаемый ответ:
# {"status":"ok","service":"maintenance-service","version":"1.0.0"}
```

---

## Типичные ошибки

### 1. Пробег не обновляется

**Симптом:** `maint:mileage:{vehicleId}` не меняется, расписания не обновляются.

**Диагностика:**
```bash
# 1. Kafka consumer lag
docker exec kafka kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group maintenance-service-mileage \
  --describe

# 2. Redis значение пробега
redis-cli GET maint:mileage:{vehicleId}

# 3. Логи MileageExtractor
docker logs maintenance-service 2>&1 | grep "MileageExtractor"
```

**Причины:**
- Kafka consumer не подключен → проверить `KAFKA_BROKERS`
- GPS-точки не содержат `odometer` → проверить Connection Manager
- Redis недоступен → проверить `REDIS_HOST`, `REDIS_PORT`

### 2. Дублирование напоминаний

**Симптом:** Одно и то же напоминание приходит несколько раз.

**Диагностика:**
```bash
# Проверить Redis-флаг
redis-cli GET maint:reminder:{scheduleId}:mileage:500
# Если null → флаг не устанавливается

# Проверить логи
docker logs maintenance-service 2>&1 | grep "reminder" | grep "duplicate"
```

**Причины:**
- Redis недоступен → fallback работает без idempotency
- TTL истёк (24 часа) → напоминание повторяется на следующий день (by design)
- Несколько инстансов без distributed lock → добавить lock

### 3. Расписание не переходит в Overdue

**Симптом:** Пробег превысил порог, но status = 'active'.

**Диагностика:**
```bash
# Проверить scheduler
docker logs maintenance-service 2>&1 | grep "OverdueCheck"

# Проверить remaining_km
psql -c "SELECT id, remaining_km, status FROM maintenance.schedules WHERE vehicle_id = 'uuid'"
```

**Причины:**
- Scheduler не работает → проверить cron-конфигурацию
- `current_odometer` не обновляется → проблема #1 (пробег)
- Триггер на odometer_readings не срабатывает → проверить функцию

### 4. Ошибка при регистрации ТО

**Симптом:** POST /complete возвращает 500.

**Диагностика:**
```bash
docker logs maintenance-service 2>&1 | grep "recordService" | tail -20

# Проверить расписание
psql -c "SELECT * FROM maintenance.schedules WHERE id = 'sched-uuid'"
```

**Причины:**
- Расписание не найдено → проверить ID
- Расписание уже completed → повторная регистрация
- Ошибка БД → проверить подключение PostgreSQL

### 5. Прогноз возвращает null

**Симптом:** `predictedNextDate` = null в `/vehicles/{id}/overview`.

**Диагностика:**
```bash
# Проверить daily_mileage
psql -c "SELECT COUNT(*) FROM maintenance.daily_mileage
         WHERE vehicle_id = 'uuid' AND date > now() - interval '30 days'"
```

**Причины:**
- Менее 7 дней данных → недостаточно для прогноза
- Средний пробег = 0 (ТС на стоянке) → деление на ноль, возвращает null
- Cron job `daily_mileage` не работает → проверить scheduler

---

## Мониторинг

### Prometheus метрики

| Метрика | Тип | Описание |
|---------|-----|----------|
| `maintenance_gps_events_processed_total` | counter | Обработано GPS-событий |
| `maintenance_odometer_updates_total` | counter | Обновлений пробега |
| `maintenance_reminders_sent_total` | counter | Отправлено напоминаний (по type) |
| `maintenance_reminders_deduplicated_total` | counter | Пропущено дублей напоминаний |
| `maintenance_schedules_overdue_total` | gauge | Просроченных расписаний |
| `maintenance_service_records_total` | counter | Зарегистрированных ТО |
| `maintenance_kafka_consumer_lag` | gauge | Отставание consumer'а |
| `maintenance_prediction_accuracy` | histogram | Точность прогнозов (дни) |
| `maintenance_lock_acquired_total` | counter | Успешных захватов lock'а |
| `maintenance_lock_failed_total` | counter | Неудачных захватов lock'а |
| `maintenance_cron_job_duration_seconds` | histogram | Время выполнения cron-задач |
| `maintenance_api_request_duration_seconds` | histogram | Время ответа REST API |

### Alert Rules

```yaml
groups:
  - name: maintenance-service
    rules:
      - alert: MaintenanceKafkaLagHigh
        expr: maintenance_kafka_consumer_lag > 10000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Maintenance Service Kafka consumer lag > 10K"

      - alert: MaintenanceOverdueSchedulesHigh
        expr: maintenance_schedules_overdue_total > 50
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "Более 50 просроченных расписаний ТО"

      - alert: MaintenanceCronJobFailed
        expr: increase(maintenance_cron_job_duration_seconds_count{status="error"}[1h]) > 0
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Cron-задание maintenance-service завершилось с ошибкой"

      - alert: MaintenanceAPILatencyHigh
        expr: histogram_quantile(0.99, rate(maintenance_api_request_duration_seconds_bucket[5m])) > 0.5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "REST API latency p99 > 500ms"

      - alert: MaintenanceLockContentionHigh
        expr: rate(maintenance_lock_failed_total[5m]) / rate(maintenance_lock_acquired_total[5m]) > 0.1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Более 10% неудачных попыток захвата lock'а"
```

### Grafana Dashboard

**Рекомендуемые панели:**
1. **GPS Events Processing Rate** — events/sec, consumer lag
2. **Odometer Updates** — обновлений/мин по ТС
3. **Reminders** — отправлено vs deduplicated, по типам
4. **Schedules Overview** — active / overdue / completed (pie chart)
5. **Cron Jobs** — время выполнения, статусы
6. **API Latency** — p50, p95, p99
7. **Lock Contention** — acquired vs failed

---

## Логи

### Ключевые маркеры

| Маркер | Уровень | Описание |
|--------|---------|----------|
| `[MileageExtractor]` | INFO | Обновление пробега |
| `[ReminderEngine]` | INFO | Отправка / дедупликация напоминания |
| `[ServiceTracker]` | INFO | Регистрация выполненного ТО |
| `[SchedulerService]` | INFO | Запуск / завершение cron-задач |
| `[IntervalCalculator]` | WARN | Аномальный delta пробега |
| `[MileageExtractor]` | ERROR | Ошибка Redis / Kafka |
| `[GpsEventsConsumer]` | ERROR | Ошибка десериализации GPS-сообщения |

### Примеры логов

```
INFO  [MileageExtractor] vehicle=v-123 odometer=52340 delta=12 thresholds_checked=3
INFO  [ReminderEngine] schedule=sched-1 type=mileage remaining=497 → sending reminder
INFO  [ReminderEngine] schedule=sched-1 type=mileage remaining=495 → deduplicated (redis flag exists)
WARN  [IntervalCalculator] vehicle=v-456 delta=1500 in 30sec → anomaly detected, skipping
INFO  [ServiceTracker] schedule=sched-1 service_recorded odometer=60120 cost=18500.0
INFO  [SchedulerService] job=overdue_check started schedules_checked=150 overdue_found=3
ERROR [GpsEventsConsumer] Failed to deserialize gps event: missing field 'vehicleId'
```
