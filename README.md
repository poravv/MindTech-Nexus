# MindTech Nexus

Integrador multi-tenant liviano entre punto-venta, Electrónico SIFEN y GiftHub. Spec: `MINDTECH_NEXUS_ESPECIFICACION_TECNICA.md`; decisiones en `docs/adr/`; plan por slices en `docs/implementation-plan.md`.

## nexus-api

Spring Boot 4.1.1 / Java 21, un módulo Maven hexagonal (`domain` → `application` → `infrastructure`, verificado con ArchUnit), PostgreSQL 16 + Flyway, `JdbcClient`.

Estado: S1 (tenants, usuarios de consola, clientes m2m con `client_credentials`, conexiones con credenciales cifradas AES-256-GCM, auditoría, problem+json, health y métricas) y S2 (motor de operaciones durable: integraciones con estados y política, cola en Postgres con `SKIP LOCKED`, idempotencia, reintentos con backoff, DLQ con replay, rate limit y concurrencia por integración).

### Correr local

```bash
cp .env.example .env            # completar; generar claves con openssl (ver comentarios)
docker compose up -d --build    # postgres (host 55440) + nexus-api (host 8090, management 8091)
curl -s localhost:8091/actuator/health/readiness    # {"status":"UP"}
```

Sin Docker para la app: `docker compose up -d postgres` y luego
`cd nexus-api && set -a && . ../.env && set +a && SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run` (puertos 8080/8081).

Tests (requiere Docker para Testcontainers; no usan `.env`):

```bash
cd nexus-api && ./mvnw -B verify
```

### Variables

| Variable | Uso |
|---|---|
| `NEXUS_DB_NAME`, `NEXUS_DB_USER`, `NEXUS_DB_PASSWORD` | Base de datos (`SPRING_DATASOURCE_URL` la sobreescribe) |
| `NEXUS_JWT_SIGNING_KEY` | base64, ≥ 256 bits. Firma HS256 de los tokens de Nexus |
| `NEXUS_MASTER_KEY` | base64, 32 bytes. Cifra las credenciales de conexiones |
| `NEXUS_BOOTSTRAP_ADMIN_EMAIL`, `NEXUS_BOOTSTRAP_ADMIN_PASSWORD` | Primer `platform_admin` si no hay usuarios; debe cambiar la contraseña al entrar |
| `NEXUS_WORKER_ENABLED` | `true` (default) corre el worker de operaciones y sus jobs en este proceso |
| `NEXUS_CONNECTORS_ALLOWED_HOSTS` | Allowlist de hosts para `baseUrl` (default `*.svc.cluster.local`; perfil `local` agrega `localhost`, `host.docker.internal`) |

Sin las claves la app no arranca (fail-fast).

### Primer tenant con curl

Contra compose (`API=http://localhost:8090`). La cookie de refresh es `Secure`; curl la envía a `localhost` igual.

```bash
API=http://localhost:8090
# 1. Login del admin bootstrap (obliga cambio de contraseña)
curl -s -c cj -H 'Content-Type: application/json' -d '{"email":"<admin>","password":"<bootstrap>"}' $API/api/admin/auth/login
curl -s -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"currentPassword":"<bootstrap>","newPassword":"<nueva, 12+>"}' $API/api/admin/auth/password
TOKEN=$(curl -s -b cj -c cj -X POST $API/api/admin/auth/refresh | jq -r .accessToken)

# 2. Tenant, cliente m2m (el secreto se muestra una sola vez) y token m2m
TENANT=$(curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"slug":"panaderia","name":"Panadería"}' $API/api/admin/tenants | jq -r .id)
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"POS","scopes":["fiscal:issue","fiscal:read"]}' "$API/api/admin/api-clients?tenantId=$TENANT"
curl -s -u <clientId>:<clientSecret> -d grant_type=client_credentials $API/oauth/token
curl -s -H "Authorization: Bearer $TOKEN" $API/api/admin/tenants
```

`platform_admin` indica el tenant con `?tenantId=`; los demás roles operan siempre sobre el tenant de su token.

### Endpoints

- `POST /oauth/token` — `client_credentials` con HTTP Basic.
- `/api/admin/auth/{login,refresh,logout,me,password}`.
- `/api/admin/tenants`, `/api/admin/users`, `/api/admin/api-clients` (`/{id}/rotate`, `/{id}/revoke`), `/api/admin/connections` (`/{id}`, `/{id}/test`), `/api/admin/audit`.
- `/api/admin/integrations` (`GET`, `POST`, `GET/PUT /{id}`, `POST /{id}/state {state, reason}`): sin `DELETE`, `DISABLED` retira una integración conservando su historial. `EMERGENCY_STOP` (entrar y salir) solo `platform_admin`; `DISABLED` requiere `tenant_admin`; `operator` alterna `ENABLED/PAUSED/DRAINING`.
- `/api/admin/operations?flow&status&from&to&q&page&size`, `/summary?from` (por estado/flujo + tamaño de la DLQ), `/{id}` (con `steps`), `POST /{id}/replay` (solo `DEAD`, auditado).
- `GET /api/v1/operations/{id}` — runtime m2m, scope `operations:read`, solo operaciones del tenant del token.
- OpenAPI: `/v3/api-docs`, `/swagger-ui.html`. Management (puerto 8081): `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`.

### Motor de operaciones (S2)

- **Cola**: tabla `operations`. El worker (`nexus.worker.enabled`) reclama cada 500 ms hasta `batch-size` (10) operaciones con `FOR UPDATE SKIP LOCKED`, lease de 10 min; un job por minuto devuelve a `RETRY` los leases vencidos. Cada paso corre fuera de transacción y su resultado se guarda con compare-and-set sobre el lease (un worker rescatado no pisa al nuevo). Apagado ordenado: deja de reclamar y espera `shutdown-timeout` (25 s) a los pasos en curso.
- **Idempotencia**: `UNIQUE(tenant_id, kind, idempotency_key)` + SHA-256 canónico del payload: mismo hash → la operación existente; distinto → 409 `idempotency-conflict`.
- **Gates por paso**: estado de la integración (`ENABLED`/`DRAINING` despachan; `PAUSED` retiene sin gastar intentos; `DISABLED`/`EMERGENCY_STOP` no despachan; nuevas solo con `ENABLED`/`PAUSED`, si no 409), credenciales descifrables (si no `DEAD credentials-missing`), intentos, rate limit y `maxInFlight` (sin cupo → `RETRY` +1 s sin gastar intento).
- **Errores** (`ErrorKind`): `TRANSIENT`/`RATE_LIMITED`/`UNCERTAIN` → `RETRY` con backoff `min(max, base·2^(n-1))` ±20 % (o `Retry-After`) hasta `DEAD attempts-exhausted`; `IN_PROGRESS` → +30 s sin gastar intento; `REJECTED` → `FAILED`; `CONFLICT`/`AUTH` → `DEAD`.
- **Backpressure**: más de `nexus.queue.max-unfinished-per-tenant` (1000) operaciones vivas → 429 `queue-saturated` con `Retry-After: 30`; base caída → 503 con `Retry-After`. Nunca 202 sin persistir.
- **Techo**: rate limit y concurrencia en memoria, válidos con **1 réplica** (ADR-002). Con 2+ réplicas reimplementar `RateLimiterPort` en Postgres/Redis.
- **Métricas** (`/actuator/prometheus`): `nexus_operations_total{flow,kind,status}`, `nexus_operation_duration_seconds`, `nexus_step_duration_seconds{flow,step}`, `nexus_queue_depth{status}`, `nexus_dead_letters_total{flow}`, `nexus_rate_limited_total{flow}`; sin `tenant_id`.
- **Enchufar un flujo (S3/S5)**: implementar `application.saga.Saga` (`kind()`, `firstStep()`, `execute(StepContext) → StepResult` = `Completed` | `Next(step, delay)` | `Failed(ConnectorError)`, y opcional `compensate`) y declararlo como bean; crear operaciones con `StartOperation`.

Errores en `application/problem+json` con `type` `https://nexus.mindtechpy.net/problems/<code>`, `code` y `correlationId` (`X-Correlation-Id`).
