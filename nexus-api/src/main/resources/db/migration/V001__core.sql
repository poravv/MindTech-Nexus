-- Núcleo de identidad, tenants, conexiones y auditoría (spec §6). Las tablas de operaciones llegan en V002 (S2).

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    slug        text        NOT NULL,
    name        text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenants_slug UNIQUE (slug)
);

CREATE TABLE users (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid REFERENCES tenants (id),
    email                text        NOT NULL,
    password_hash        text        NOT NULL,
    role                 text        NOT NULL CHECK (role IN ('platform_admin', 'tenant_admin', 'operator', 'auditor', 'viewer')),
    status               text        NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    must_change_password boolean     NOT NULL DEFAULT true,
    failed_attempts      int         NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_tenant_by_role CHECK ((role = 'platform_admin') = (tenant_id IS NULL))
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
CREATE INDEX idx_users_tenant ON users (tenant_id);

CREATE TABLE user_sessions (
    id           uuid PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id),
    refresh_hash text        NOT NULL,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    ip           text,
    user_agent   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_sessions_user ON user_sessions (user_id);
CREATE UNIQUE INDEX uq_user_sessions_refresh_hash ON user_sessions (refresh_hash);

CREATE TABLE api_clients (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenants (id),
    client_id            text        NOT NULL,
    name                 text        NOT NULL,
    secret_hash          text        NOT NULL,
    secret_previous_hash text,
    secret_rotated_at    timestamptz,
    scopes               text[]      NOT NULL,
    status               text        NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    last_used_at         timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_api_clients_client_id UNIQUE (client_id)
);
CREATE INDEX idx_api_clients_tenant ON api_clients (tenant_id);

CREATE TABLE connections (
    id              uuid PRIMARY KEY,
    tenant_id       uuid        NOT NULL REFERENCES tenants (id),
    connector       text        NOT NULL CHECK (connector IN ('SIFEN', 'POS', 'GIFTHUB')),
    name            text        NOT NULL,
    base_url        text        NOT NULL,
    credentials_enc bytea       NOT NULL,
    settings        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    last_test_at    timestamptz,
    last_test_ok    boolean,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_connections_tenant_connector_name UNIQUE (tenant_id, connector, name)
);

CREATE TABLE audit_events (
    id             uuid PRIMARY KEY,
    tenant_id      uuid REFERENCES tenants (id),
    actor_type     text        NOT NULL CHECK (actor_type IN ('USER', 'CLIENT', 'SYSTEM')),
    actor_id       text        NOT NULL,
    action         text        NOT NULL,
    resource_type  text        NOT NULL,
    resource_id    text,
    before         jsonb,
    after          jsonb,
    correlation_id text,
    happened_at    timestamptz NOT NULL
);
CREATE INDEX idx_audit_events_tenant_happened ON audit_events (tenant_id, happened_at DESC);
