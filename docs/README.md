# 🔧 Maintenance Service — Сервис планового ТО

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

## Обзор

**Maintenance Service** — сервис Block 2 (Business Logic) для управления техническим обслуживанием
транспортных средств. Автоматически отслеживает пробег из GPS, планирует ТО, отправляет напоминания
и ведёт историю обслуживания.

| Параметр | Значение |
|----------|----------|
| **Блок** | 2 — Business Logic |
| **Порт** | 8087 (REST API) |
| **БД** | PostgreSQL (схема `maintenance`) |
| **Кеш** | Redis (пробег, расписания, блокировки) |
| **Kafka** | Consume: `gps-events` · Produce: `maintenance-events` |

## Основные функции

### 1. Шаблоны ТО
- Создание шаблонов с интервалами (пробег / моточасы / дни / комбинированный)
- Список работ с артикулами запчастей и стоимостью
- Настройки напоминаний (500км, 100км, 7 дней, 1 день)
- Приоритеты: Critical, High, Normal, Low

### 2. Расписания обслуживания
- Привязка шаблона к конкретному ТС
- Автоматическое обновление пробега из GPS-событий
- Статусы: Active → Overdue → Completed / Paused
- Пересчёт «следующего ТО» после выполнения

### 3. Отслеживание пробега
- Kafka consumer: GPS-точки с полем `odometer`
- Redis-кэш текущего пробега для быстрой проверки порогов
- Суточная агрегация: `daily_mileage` таблица

### 4. Напоминания
- Многоуровневые: 500км, 100км, 7 дней, 1 день до ТО
- Защита от дублей через Redis флаги
- Каналы: push, email, SMS

### 5. История обслуживания
- Регистрация выполненного ТО с деталями работ
- Стоимость: работы + запчасти
- Пересчёт расписания после выполнения

## Быстрый старт

```bash
# 1. Поднять инфраструктуру
cd ../../test-stand && docker-compose up -d postgres redis kafka

# 2. Запустить сервис
cd ../services/maintenance-service
sbt run

# 3. Health check
curl http://localhost:8087/health

# 4. Создать шаблон ТО
curl -X POST http://localhost:8087/api/v1/maintenance/templates \
  -H "Content-Type: application/json" \
  -d '{"name":"ТО-1","intervalType":"combined","mileageInterval":15000,"daysInterval":365,"priority":"normal","estimatedDuration":120,"items":[],"reminders":[]}'
```

## Переменные окружения

| Переменная | По умолчанию | Описание |
|------------|-------------|----------|
| `HTTP_PORT` | `8087` | Порт REST API |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/tracker` | PostgreSQL URL |
| `DATABASE_USER` | `tracker` | Пользователь БД |
| `DATABASE_PASSWORD` | — | Пароль БД |
| `REDIS_HOST` | `localhost` | Redis хост |
| `REDIS_PORT` | `6379` | Redis порт |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka bootstrap servers |

## Связанные документы

- [ARCHITECTURE.md](ARCHITECTURE.md) — Внутренняя архитектура, 9 компонентов
- [API.md](API.md) — REST API: шаблоны, расписания, записи ТО, пробег
- [DATA_MODEL.md](DATA_MODEL.md) — PostgreSQL схема `maintenance`, Redis ключи
- [KAFKA.md](KAFKA.md) — Kafka: gps-events → maintenance-events
- [DECISIONS.md](DECISIONS.md) — Архитектурные решения
- [RUNBOOK.md](RUNBOOK.md) — Запуск, дебаг, ошибки
- [INDEX.md](INDEX.md) — Содержание документации
- [docs/services/MAINTENANCE_SERVICE.md](../../../docs/services/MAINTENANCE_SERVICE.md) — Системный дизайн-документ
