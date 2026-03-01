# 🔧 Maintenance Service — Архитектурные решения (ADR)

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

---

## ADR-001: Combined интервалы (первый достигнутый порог)

**Статус:** Принято  
**Дата:** 2026-01

### Контекст
Для ТО часто нужно комбинировать несколько критериев: «каждые 15 000 км ИЛИ раз в год — что раньше».

### Решение
Тип интервала `combined` вычисляет оставшийся ресурс по КАЖДОМУ критерию и берёт **минимальный**.

```scala
def calculateRemaining(schedule: Schedule, template: Template): Int =
  val remainders = List(
    template.mileageInterval.map(i => schedule.nextServiceAt - schedule.currentOdometer),
    template.daysInterval.map(i => ChronoUnit.DAYS.between(Instant.now, schedule.nextServiceDate)),
    template.engineHoursInterval.map(i => schedule.nextEngineHoursAt - schedule.currentEngineHours)
  ).flatten
  remainders.min
```

### Последствия
- Гибкость: поддержка любой комбинации критериев
- Усложнение: каждый consumer должен обрабатывать все типы
- Напоминания генерируются по ближайшему критерию

---

## ADR-002: Redis-флаги для идемпотентных напоминаний

**Статус:** Принято  
**Дата:** 2026-01

### Контекст
GPS-точки приходят часто (каждые 10-60 сек). При каждом обновлении пробега проверяются пороги.
Если ТС на границе порога (например, ровно 500 км до ТО), напоминание может генерироваться
при каждой GPS-точке.

### Решение
Использовать `SETNX` в Redis с ключом `maint:reminder:{scheduleId}:{type}:{value}`:
- Если ключ НЕ существует → установить + отправить напоминание
- Если ключ существует → пропустить (уже отправлено)
- TTL = 24 часа (автоочистка)

### Альтернативы
1. **Флаг в PostgreSQL** — отклонено, слишком медленно при 10k GPS/sec
2. **In-memory Set** — отклонено, теряется при рестарте, не работает с несколькими инстансами
3. **Kafka exactly-once** — излишне сложно для данного случая

### Последствия
- Нулевой дубликат напоминаний даже при высокой нагрузке
- При рестарте сервиса — Redis-флаги сохраняются
- Автоочистка через TTL (не нужен отдельный cleanup)

---

## ADR-003: Distributed Lock для обновления пробега

**Статус:** Принято  
**Дата:** 2026-02

### Контекст
При горизонтальном масштабировании consumer'а GPS-событий возможна ситуация race condition:
два инстанса одновременно обновляют пробег одного ТС.

### Решение
Redis distributed lock с коротким TTL (5 секунд):

```scala
def withMileageLock[A](vehicleId: UUID)(action: => Task[A]): Task[A] =
  val lockKey = s"maint:lock:mileage:$vehicleId"
  for
    acquired <- redis.setNx(lockKey, instanceId).flatMap(ok =>
      if ok then redis.expire(lockKey, 5.seconds).as(true)
      else ZIO.succeed(false)
    )
    result <- if acquired then action.ensuring(redis.del(lockKey))
              else ZIO.fail(LockNotAcquired(vehicleId))
  yield result
```

### Последствия
- Сериализация обновлений для одного ТС
- При сбое — автоматическое освобождение через TTL
- Минимальный overhead (5ms на lock/unlock)

---

## ADR-004: Суточная агрегация пробега (cron job)

**Статус:** Принято  
**Дата:** 2026-02

### Контекст
Для прогнозирования даты следующего ТО нужен среднесуточный пробег.
Вычислять его в реальном времени по каждой GPS-точке — дорого.

### Решение
Cron-задание в 23:55 UTC ежедневно:
1. Выбрать `odometer_readings` за текущий день, сгруппировать по `vehicle_id`
2. Рассчитать `distance = max(odometer) - min(odometer)`
3. Записать в `daily_mileage`

### Альтернативы
1. **Continuous Aggregate (TimescaleDB)** — отклонено, данные в PostgreSQL (не TimescaleDB)
2. **Real-time вычисление** — отклонено, избыточная нагрузка на БД
3. **Kafka Streams** — отклонено, overkill для ежедневной задачи

### Последствия
- Простота реализации: обычный SQL
- Данные доступны с задержкой ≤ 24 часа (приемлемо для прогнозов)
- Таблица `daily_mileage` компактна и эффективна для запросов

---

## ADR-005: Prediction Engine на основе скользящего среднего

**Статус:** Принято  
**Дата:** 2026-03

### Контекст
Пользователи хотят видеть прогнозируемую дату следующего ТО.

### Решение
Скользящее среднее за 30 дней из `daily_mileage`:

```scala
def predictNextServiceDate(vehicleId: UUID, remainingKm: Int): Task[Option[LocalDate]] =
  for
    avgDaily <- dailyMileageRepo.getAvgLast30Days(vehicleId)
    prediction = avgDaily.filter(_ > 0).map { avg =>
      val daysRemaining = (remainingKm.toDouble / avg).ceil.toInt
      LocalDate.now().plusDays(daysRemaining)
    }
  yield prediction
```

### Альтернативы
1. **ML-модель** — отклонено для MVP, излишняя сложность
2. **Линейная регрессия** — рассмотреть в v2.0
3. **Без прогноза** — отклонено, пользователи активно запрашивали эту фичу

### Последствия
- Простой и надёжный подход для MVP
- Не учитывает сезонность и графики работы → рассмотреть ML в будущем
- Требует ≥ 7 дней данных для корректного прогноза
