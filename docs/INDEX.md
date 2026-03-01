# Maintenance Service — Содержание документации

> Тег: `АКТУАЛЬНО` | Обновлён: `2026-06-02` | Версия: `1.0`

| Файл | Описание |
|------|----------|
| [README.md](README.md) | Что делает, как запустить, переменные окружения |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Внутренняя архитектура, 9 компонентов, Mermaid диаграммы |
| [API.md](API.md) | REST API: шаблоны, расписания, записи ТО, пробег |
| [DATA_MODEL.md](DATA_MODEL.md) | PostgreSQL схема `maintenance`, Redis ключи |
| [KAFKA.md](KAFKA.md) | Kafka: gps-events (consume), maintenance-events (produce) |
| [DECISIONS.md](DECISIONS.md) | ADR — архитектурные решения |
| [RUNBOOK.md](RUNBOOK.md) | Запуск, дебаг, типичные ошибки, мониторинг |

## Связанные документы (инфраструктура)

- [infra/kafka/TOPICS.md](../../../infra/kafka/TOPICS.md) — Все Kafka топики
- [infra/databases/](../../../infra/databases/) — Схемы БД
- [docs/services/MAINTENANCE_SERVICE.md](../../../docs/services/MAINTENANCE_SERVICE.md) — Системный дизайн-документ
