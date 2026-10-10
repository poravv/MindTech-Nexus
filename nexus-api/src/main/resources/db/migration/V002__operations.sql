-- Motor de operaciones durable (spec §6-§7, ADR-002). entity_mappings e inbound_events se crean acá para
-- que S3 y S5 (en paralelo) no compitan por la misma versión de migración.

CREATE TABLE integrations (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenants (id),
    flow                 text        NOT NULL CHECK (flow IN ('FISCAL', 'ORDER', 'CATALOG')),
    code                 text        NOT NULL,
    source_connection_id uuid REFERENCES connections (id),
    target_connection_id uuid        NOT NULL REFERENCES connections (id),
    state                text        NOT NULL CHECK (state IN ('ENABLED', 'PAUSED', 'DRAINING', 'DISABLED', 'EMERGENCY_STOP')),
    policy               jsonb       NOT NULL,
    version              int         NOT NULL DEFAULT 1,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_integrations_tenant_code UNIQUE (tenant_id, code)
);

CREATE TABLE operations (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    integration_id  uuid        NOT NULL REFERENCES integrations (id),
    flow            text        NOT NULL CHECK (flow IN ('FISCAL', 'ORDER', 'CATALOG')),
    kind            text        NOT NULL CHECK (kind IN ('ISSUE_INVOICE', 'CANCEL_INVOICE', 'CREATE_ORDER', 'ORDER_STATUS', 'CATALOG_SYNC')),
    idempotency_key text        NOT NULL,
    request_hash    text        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'FAILED', 'DEAD')),
    current_step    text,
    attempts        int         NOT NULL DEFAULT 0,
    max_attempts    int         NOT NULL,
    next_run_at     timestamptz NOT NULL,
    locked_by       text,
    locked_until    timestamptz,
    priority        smallint    NOT NULL DEFAULT 100,
    correlation_id  text,
    payload         jsonb,
    result          jsonb,
    last_error      jsonb,
    dead_reason     text,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_operations_tenant_kind_key UNIQUE (tenant_id, kind, idempotency_key)
);
CREATE INDEX idx_operations_status_next_run ON operations (status, next_run_at);
CREATE INDEX idx_operations_tenant_created ON operations (tenant_id, created_at DESC);
-- Conteo de backpressure por tenant: solo filas vivas.
CREATE INDEX idx_operations_tenant_unfinished ON operations (tenant_id) WHERE status IN ('PENDING', 'RETRY', 'RUNNING');

CREATE TABLE operation_steps (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    operation_id  uuid        NOT NULL REFERENCES operations (id),
    step          text        NOT NULL,
    attempt       int         NOT NULL,
    status        text        NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    external_ref  text,
    response_code int,
    error_code    text,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL
);
CREATE INDEX idx_operation_steps_operation ON operation_steps (operation_id);

CREATE TABLE entity_mappings (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    connection_id uuid        NOT NULL REFERENCES connections (id),
    entity_type   text        NOT NULL CHECK (entity_type IN ('CUSTOMER', 'PRODUCT', 'ORDER', 'INVOICE')),
    internal_id   text        NOT NULL,
    external_id   text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity_mappings_internal UNIQUE (tenant_id, connection_id, entity_type, internal_id),
    CONSTRAINT uq_entity_mappings_external UNIQUE (tenant_id, connection_id, entity_type, external_id)
);

CREATE TABLE inbound_events (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenants (id),
    connection_id uuid        NOT NULL REFERENCES connections (id),
    source        text        NOT NULL CHECK (source IN ('GIFTHUB', 'SIFEN')),
    event_id      text        NOT NULL,
    event_type    text        NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    operation_id  uuid REFERENCES operations (id),
    CONSTRAINT uq_inbound_events_connection_event UNIQUE (connection_id, event_id)
);
CREATE INDEX idx_inbound_events_received ON inbound_events (received_at);
