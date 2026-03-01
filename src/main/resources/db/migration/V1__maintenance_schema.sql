-- ============================================================
-- Flyway V1: Maintenance Service Schema
-- База: tracker_devices (общая), Схема: maintenance
-- ============================================================

CREATE SCHEMA IF NOT EXISTS maintenance;

-- ---- ENUM типы ----

CREATE TYPE maintenance.interval_type AS ENUM ('Mileage', 'EngineHours', 'Calendar', 'Combined');
CREATE TYPE maintenance.service_priority AS ENUM ('Critical', 'High', 'Normal', 'Low');
CREATE TYPE maintenance.schedule_status AS ENUM ('Active', 'Paused', 'Overdue', 'Completed');
CREATE TYPE maintenance.service_type AS ENUM ('Scheduled', 'Unscheduled', 'Emergency', 'Inspection');

-- ---- Шаблоны ТО ----

CREATE TABLE maintenance.templates (
    id                         UUID PRIMARY KEY,
    company_id                 UUID NOT NULL,
    name                       VARCHAR(255) NOT NULL,
    description                TEXT,
    vehicle_type               VARCHAR(100),
    interval_type              maintenance.interval_type NOT NULL,
    interval_mileage_km        BIGINT,
    interval_engine_hours      INT,
    interval_days              INT,
    priority                   maintenance.service_priority NOT NULL DEFAULT 'Normal',
    estimated_duration_minutes INT,
    estimated_cost_rub         NUMERIC(12, 2),
    is_active                  BOOLEAN NOT NULL DEFAULT true,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_template_name_company UNIQUE (company_id, name)
);

CREATE INDEX idx_templates_company ON maintenance.templates (company_id);
CREATE INDEX idx_templates_vehicle_type ON maintenance.templates (vehicle_type) WHERE vehicle_type IS NOT NULL;

-- ---- Работы в шаблоне ----

CREATE TABLE maintenance.template_items (
    id               UUID PRIMARY KEY,
    template_id      UUID NOT NULL REFERENCES maintenance.templates(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    part_number      VARCHAR(100),
    estimated_cost_rub NUMERIC(12, 2),
    sort_order       INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_template_items_template ON maintenance.template_items (template_id);

-- ---- Настройки напоминаний ----

CREATE TABLE maintenance.reminder_configs (
    id             UUID PRIMARY KEY,
    template_id    UUID NOT NULL REFERENCES maintenance.templates(id) ON DELETE CASCADE,
    threshold_km   BIGINT,
    threshold_days INT,
    threshold_hours INT
);

CREATE INDEX idx_reminder_configs_template ON maintenance.reminder_configs (template_id);

-- ---- Расписания ТО ----

CREATE TABLE maintenance.schedules (
    id                         UUID PRIMARY KEY,
    company_id                 UUID NOT NULL,
    vehicle_id                 UUID NOT NULL,
    template_id                UUID NOT NULL REFERENCES maintenance.templates(id),
    template_name              VARCHAR(255) NOT NULL,
    interval_type              maintenance.interval_type NOT NULL,
    interval_mileage_km        BIGINT,
    interval_engine_hours      INT,
    interval_days              INT,
    priority                   maintenance.service_priority NOT NULL DEFAULT 'Normal',
    status                     maintenance.schedule_status NOT NULL DEFAULT 'Active',
    -- Текущие значения
    current_mileage_km         BIGINT NOT NULL DEFAULT 0,
    current_engine_hours       INT NOT NULL DEFAULT 0,
    -- Последнее ТО
    last_service_date          DATE,
    last_service_mileage_km    BIGINT,
    last_service_engine_hours  INT,
    -- Следующее ТО
    next_service_mileage_km    BIGINT,
    next_service_engine_hours  INT,
    next_service_date          DATE,
    -- Оставшееся до ТО
    remaining_km               BIGINT,
    remaining_engine_hours     INT,
    remaining_days             INT,
    -- Флаги напоминаний (идемпотентность)
    reminder_first_sent        BOOLEAN NOT NULL DEFAULT false,
    reminder_second_sent       BOOLEAN NOT NULL DEFAULT false,
    reminder_third_sent        BOOLEAN NOT NULL DEFAULT false,
    reminder_overdue_sent      BOOLEAN NOT NULL DEFAULT false,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_schedule_vehicle_template UNIQUE (vehicle_id, template_id)
);

CREATE INDEX idx_schedules_company ON maintenance.schedules (company_id);
CREATE INDEX idx_schedules_vehicle ON maintenance.schedules (vehicle_id);
CREATE INDEX idx_schedules_status ON maintenance.schedules (status);
CREATE INDEX idx_schedules_overdue ON maintenance.schedules (status, remaining_km, remaining_days)
    WHERE status = 'Active';

-- ---- Записи о ТО ----

CREATE TABLE maintenance.service_records (
    id                       UUID PRIMARY KEY,
    company_id               UUID NOT NULL,
    vehicle_id               UUID NOT NULL,
    schedule_id              UUID REFERENCES maintenance.schedules(id),
    service_type             maintenance.service_type NOT NULL,
    description              TEXT NOT NULL,
    mileage_km               BIGINT NOT NULL,
    engine_hours             INT,
    service_date             DATE NOT NULL,
    performed_by             VARCHAR(255),
    workshop                 VARCHAR(255),
    total_cost_rub           NUMERIC(12, 2),
    notes                    TEXT,
    attachments              TEXT,
    next_service_mileage_km  BIGINT,
    next_service_date        DATE,
    created_by               UUID NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_service_records_vehicle ON maintenance.service_records (vehicle_id, service_date DESC);
CREATE INDEX idx_service_records_company ON maintenance.service_records (company_id, service_date DESC);

-- ---- Работы при ТО ----

CREATE TABLE maintenance.service_record_items (
    id                UUID PRIMARY KEY,
    service_record_id UUID NOT NULL REFERENCES maintenance.service_records(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    part_number       VARCHAR(100),
    quantity          INT NOT NULL DEFAULT 1,
    cost_rub          NUMERIC(12, 2),
    notes             TEXT
);

CREATE INDEX idx_service_record_items_record ON maintenance.service_record_items (service_record_id);

-- ---- Показания одометра ----

CREATE TABLE maintenance.odometer_readings (
    id          UUID PRIMARY KEY,
    vehicle_id  UUID NOT NULL,
    mileage_km  BIGINT NOT NULL,
    source      VARCHAR(50) NOT NULL DEFAULT 'gps',
    recorded_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_odometer_vehicle_time ON maintenance.odometer_readings (vehicle_id, recorded_at DESC);

-- ---- Показания моточасов ----

CREATE TABLE maintenance.engine_hours_readings (
    vehicle_id    UUID NOT NULL,
    engine_hours  INT NOT NULL,
    source        VARCHAR(50) NOT NULL DEFAULT 'gps',
    recorded_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (vehicle_id, recorded_at)
);

-- ---- Суточный пробег ----

CREATE TABLE maintenance.daily_mileage (
    vehicle_id      UUID NOT NULL,
    date            DATE NOT NULL,
    start_mileage_km BIGINT NOT NULL,
    end_mileage_km  BIGINT NOT NULL,
    distance_km     BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (vehicle_id, date)
);

-- ---- Журнал напоминаний ----

CREATE TABLE maintenance.reminders_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id   UUID NOT NULL REFERENCES maintenance.schedules(id),
    vehicle_id    UUID NOT NULL,
    company_id    UUID NOT NULL,
    reminder_type VARCHAR(100) NOT NULL,
    remaining_km  BIGINT,
    remaining_days INT,
    sent_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged  BOOLEAN NOT NULL DEFAULT false
);

CREATE INDEX idx_reminders_schedule ON maintenance.reminders_log (schedule_id, sent_at DESC);

-- ---- Представления ----

-- Обзор ТО по транспорту
CREATE VIEW maintenance.vehicle_overview AS
SELECT
    s.vehicle_id,
    s.company_id,
    COUNT(*) FILTER (WHERE s.status = 'Active') AS active_count,
    COUNT(*) FILTER (WHERE s.status = 'Overdue') AS overdue_count,
    MIN(s.remaining_km) FILTER (WHERE s.remaining_km > 0) AS min_remaining_km,
    MIN(s.remaining_days) FILTER (WHERE s.remaining_days > 0) AS min_remaining_days,
    MAX(sr.service_date) AS last_service_date
FROM maintenance.schedules s
LEFT JOIN maintenance.service_records sr ON sr.vehicle_id = s.vehicle_id
GROUP BY s.vehicle_id, s.company_id;

-- Статистика шаблонов
CREATE VIEW maintenance.template_stats AS
SELECT
    t.id AS template_id,
    t.name,
    t.company_id,
    COUNT(DISTINCT s.id) AS schedule_count,
    COUNT(DISTINCT sr.id) AS service_count,
    AVG(sr.total_cost_rub) AS avg_cost
FROM maintenance.templates t
LEFT JOIN maintenance.schedules s ON s.template_id = t.id
LEFT JOIN maintenance.service_records sr ON sr.schedule_id = s.id
GROUP BY t.id, t.name, t.company_id;

-- ---- Триггер обновления updated_at ----

CREATE OR REPLACE FUNCTION maintenance.update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_templates_updated_at
    BEFORE UPDATE ON maintenance.templates
    FOR EACH ROW EXECUTE FUNCTION maintenance.update_updated_at();

CREATE TRIGGER trg_schedules_updated_at
    BEFORE UPDATE ON maintenance.schedules
    FOR EACH ROW EXECUTE FUNCTION maintenance.update_updated_at();

CREATE TRIGGER trg_service_records_updated_at
    BEFORE UPDATE ON maintenance.service_records
    FOR EACH ROW EXECUTE FUNCTION maintenance.update_updated_at();
