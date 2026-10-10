# MindTech Nexus — Especificación técnica v1.1 (MVP Lean)

> **Versión 1.1 — 10/10/2026.** Reemplaza a la v1.0, que se escribió sin inventario. Esta versión está corregida contra el código real de `punto-venta`, `electronico-sifen-backend`, `gift-hub` y `k8s-platforms` (ver `docs/discovery.md`) y refleja las decisiones ya tomadas (ver `docs/adr/`). Lo que quedó fuera del MVP está en la sección 16 "Evolución".
> **Propietario:** MindTechPy. **Repo:** `github.com/poravv/MindTech-Nexus` (público, hoy vacío). **Idioma del código:** inglés; docs, logs y mensajes de UI: español.

## 0. Qué cambió respecto a la v1.0

| Tema | v1.0 decía | v1.1 (realidad / decisión) |
|---|---|---|
| Deployables | `nexus-api` + `nexus-worker` + `nexus-web` | **Un solo Spring Boot `nexus-api`** (API runtime + admin API + worker, worker activable por `nexus.worker.enabled`) + `nexus-console` (Angular, nginx) |
| Infraestructura | Postgres + Redis + RabbitMQ + Keycloak | **Solo PostgreSQL.** Cola/outbox con `FOR UPDATE SKIP LOCKED`; rate limit y concurrencia en memoria (1 réplica); auth propia. Puertos definidos para migrar después |
| Stack | Java 25 / Spring Boot 4.1 "a confirmar" | **Java 21 LTS + Spring Boot 4.1.1** (verificado en Maven Central, ago-2026; la línea 3.5 cerró en jun-2026). sifen-backend usa Java 21 / Boot 3.5.16, misma imagen base `eclipse-temurin:21-jre`. **Angular 22.2** |
| Ingress | ingress-nginx, cert-manager, MetalLB | **Traefik v3 + Gateway API (`HTTPRoute` → `public-gateway/traefik`) + cloudflared.** TLS lo termina Cloudflare. Las demás apps viven en `*.mindtechpy.net`; la consola usa **`nexus.mindtechpy.net`** (DNS ya creado por el usuario hacia el túnel) |
| Postgres | compartido | **Uno por app** (Deployment + PVC Longhorn). Nexus lleva el suyo en el namespace `nexus` |
| Secretos | External Secrets / Vault | **Secret de K8s creado por CI** (`kubectl create secret --dry-run \| apply`). Credenciales de conexiones por tenant **cifradas AES-256-GCM en DB** con master key del Secret |
| Contrato SIFEN | asumido asíncrono, lotes, "Idempotency-Key" | **Síncrono** `POST /api/v1/facturas` (hasta ~150 s). Idempotencia por `transactionId` + hash de payload (200 repetido / 409 conflicto / 409 `EMISION_EN_CURSO`). Auth `X-Access-Key`+`X-Secret-Key` + **HMAC obligatorio en producción**. Numeración y timbrado los pone SIFEN. Webhooks firmados existen pero solo los registra un SUPER_ADMIN de SIFEN |
| POS → SIFEN | "existe algo, inventariar" | Existe un módulo completo (`invoicing/`, 838 líneas de cliente, BullMQ, polling por `setInterval`) **apagado en prod** (`ELECTRONIC_INVOICE_ENABLED=false`, 0 timbrados). Tiene un bug: unique **global** sobre `external_transaction_id` con `transactionId = saleNumber` que se repite entre orgs. **Se retira completo** y se reemplaza por un cliente a Nexus |
| GiftHub | "bot con carrito, ver si habla con POS" | Tiene **API de integraciones v1 push** (`PUT /products/bulk`, `/stock`, `/orders/:id/status`, `/orders/:id/paid`), webhooks `order.*` firmados HMAC y nunca llama a un POS. **Nexus actúa como socio de esa API**: casi no hay que tocar GiftHub |
| POS como destino | "endpoint atómico recomendado" | Confirmado que falta: **no hay auth m2m, ni pedido externo, ni catálogo para canal, ni cliente por teléfono, ni webhooks salientes**. Se agregan en el slice S4 |
| Red | NetworkPolicy "a definir" | **Las NetworkPolicies de `punto-venta` y `sifen` bloquean cualquier namespace distinto de `traefik`.** Hay que abrir ingress desde `nexus` en los dos repos |
| Estructura Maven | 12 módulos | **1 módulo** con paquetes `domain / application / infrastructure` + ArchUnit |
| Identidad humana | Keycloak OIDC | Usuarios en DB (bcrypt) + JWT corto + refresh cookie; roles `platform_admin / tenant_admin / operator / auditor / viewer`; `IdentityProviderPort` para OIDC futuro |

## 1. Alcance del MVP

Tres flujos, un tenant piloto por producto (POS "org" ↔ tenant Nexus ↔ emisor SIFEN ↔ tenant GiftHub):

1. **Fiscal** `[NEXUS-FISCAL]`: POS → Nexus → Electrónico SIFEN. Emisión de factura / nota de crédito, consulta de estado, cancelación, KuDE. Reemplaza por completo la integración directa del POS.
2. **Pedidos** `[NEXUS-ORDER]`: GiftHub (webhook `order.created`) → Nexus → POS (venta `PENDING` de canal) → Nexus → GiftHub (`CONFIRMED` + número de venta). Estados de vuelta: POS cobra/entrega o cancela → Nexus → GiftHub (`paid` + `DELIVERED` / `CANCELLED`).
3. **Catálogo** `[NEXUS-CATALOG]`: POS → Nexus → GiftHub (`PUT /products/bulk`), programado por integración y ejecutable a mano desde la consola.

Más: consola de administración (tenants, clientes m2m, conexiones, integraciones con estados, operaciones, DLQ con replay, auditoría), auth propia, observabilidad Prometheus/Loki, GitOps.

**No objetivos** (sin cambios respecto a v1.0): Nexus no calcula impuestos ni CDC, no toca tablas del POS, no es pasarela de pagos, no hace suscripciones, no tiene editor visual de flujos.

## 2. Inventario real (resumen)

Detalle con archivo:línea en `docs/discovery.md`.

| Sistema | Stack | Namespace / Service ClusterIP | Auth para Nexus | Lo que falta |
|---|---|---|---|---|
| **punto-venta** | NestJS 10, TypeORM, PG16, Redis/BullMQ, pnpm, Node 22 | `punto-venta` / `http://backend.punto-venta.svc.cluster.local:3000/api` | Solo JWT humano (15 min). **No hay API keys** | API key m2m, `/api/integration/*`, canal+ref externa en `sales`, teléfono E.164 en `customers`, listener hacia Nexus |
| **electronico-sifen-backend** | Spring Boot 3.5.16, Java 21, PG15, Redis, Liquibase | `sifen` / `http://backend.sifen.svc.cluster.local:8081` (actuator `:8082`) | `X-Access-Key`/`X-Secret-Key` + HMAC (`X-Timestamp`,`X-Nonce`,`X-Signature`), scopes por emisor | Nada en código. Operativo: crear emisor/credencial para el tenant piloto, registrar webhook (SUPER_ADMIN) |
| **gift-hub** | Fastify 5, Prisma 6, PG16, Node 22, React 19 | `comercio-central` / `http://api.comercio-central.svc.cluster.local:3000` | `Authorization: Bearer gh_live_…` por tenant; webhooks HMAC `X-GiftHub-Timestamp` + `X-GiftHub-Delivery` | Guardar `externalOrderId` en `Order` (opcional pero recomendado); nada más en v1 |
| **k8s-platforms** | Argo CD app-of-apps, Traefik GW API, cloudflared, Longhorn, kube-prometheus-stack, Loki/Promtail | — | — | `argocd/apps/nexus.yaml`; abrir NetworkPolicies de POS y SIFEN; CNAME `nexus.mindtechpy.net` |

## 3. Arquitectura

### 3.1 Un deployable, hexagonal, sin monstruo

```
nexus-api (Spring Boot 4.1.1, Java 21)         nexus-console (Angular 22 + nginx)
┌───────────────────────────────────────┐      ┌───────────────────────────┐
│ infrastructure/                       │      │ /            → SPA        │
│   web/runtime   /api/v1/*   (m2m JWT) │◄─────│ /api/admin/* → proxy      │
│   web/admin     /api/admin/* (users)  │      │ /api/v1/webhooks/* → proxy│
│   web/webhooks  /api/v1/webhooks/*    │      └───────────────────────────┘
│   security, persistence(jdbc+flyway), │
│   connectors/{sifen,pos,gifthub},     │
│   jobs (worker SKIP LOCKED), config   │
│ application/  casos de uso + sagas +  │
│               puertos salientes       │
│ domain/       entidades, VOs, reglas  │
└───────────────────────────────────────┘
            │ JDBC
      PostgreSQL 16 (namespace nexus)
```

- **Regla de dependencias** (ArchUnit, test obligatorio): `domain` no importa `org.springframework`, `jakarta`, `com.fasterxml`/`tools.jackson` ni `infrastructure`; `application` solo importa `domain`; `infrastructure` implementa los puertos. El worker es una clase de `infrastructure.jobs` que corre solo si `nexus.worker.enabled=true` (default `true`; permite separar el proceso después sin tocar código).
- **Persistencia**: `JdbcClient` + SQL explícito + Flyway. Sin JPA: las consultas críticas (`SKIP LOCKED`, upserts, conteos) se leen tal cual. JSON con Jackson 3 (`tools.jackson`, el default de Boot 4).
- **Clientes HTTP**: `RestClient` (Spring 7) con timeouts por conector; un bean por conector, base URL resuelta por conexión y validada contra allowlist.
- **Puertos definidos aunque la implementación v1 sea Postgres/memoria**: `JobQueuePort` (impl `PostgresJobQueue`), `RateLimiterPort` (impl `InMemoryTokenBucket`), `IdentityProviderPort` (impl `LocalUserIdentityProvider`), `SecretCipherPort` (impl `AesGcmSecretCipher`). No se crea `CachePort` hasta que exista un caso de uso que lo necesite.

### 3.2 Estructura del repositorio

```
MindTech-Nexus/
├── README.md
├── MINDTECH_NEXUS_ESPECIFICACION_TECNICA.md
├── docs/ (discovery.md, implementation-plan.md, adr/)
├── nexus-api/
│   ├── pom.xml
│   ├── Dockerfile                         # maven:3.9-eclipse-temurin-21 → eclipse-temurin:21-jre, uid 10001
│   └── src/main/java/com/mindtechpy/nexus/
│       ├── domain/        {tenant, identity, connection, integration, operation, fiscal, order, catalog, audit}
│       ├── application/   {usecase/*, saga/*, port/in/*, port/out/*}
│       └── infrastructure/{web, security, persistence, connectors, jobs, config}
│   └── src/main/resources/db/migration/V001__*.sql …
│   └── src/test/java/… (ArchitectureTest, Testcontainers, WireMock)
├── nexus-console/                         # Angular 22, standalone, signals; Dockerfile nginx-unprivileged
├── k8s/                                   # manifests que Argo sincroniza (path "k8s")
├── compose.yaml                           # local: nexus + postgres; perfil e2e: POS + GiftHub + WireMock-SIFEN
└── .github/workflows/{ci.yml, deploy.yml}
```

## 4. Topología Kubernetes real

- **Namespace `nexus`**, manifests en `MindTech-Nexus/k8s/`, Application `k8s-platforms/argocd/apps/nexus.yaml` (`repoURL: https://github.com/poravv/MindTech-Nexus.git`, `path: k8s`, `automated: {selfHeal: true}` sin `prune` por el PVC, sin `recurse: false`). El repo es público: no hace falta deploy key ni Secret `repo-*`.
- **Servicios**:
  - `nexus-api` ClusterIP `8080` (http) y `8081` (management, `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`). `replicas: 1`, sin HPA (el worker es único por diseño en v1).
  - `nexus-console` ClusterIP `8080` (nginx unprivileged, uid 101, `readOnlyRootFilesystem`), `replicas: 1`.
  - `postgres` ClusterIP `5432`, `postgres:16-alpine` pineado a minor, PVC 5Gi `longhorn`, `PGDATA=/var/lib/postgresql/data/pgdata`.
- **Exposición pública**: solo `nexus-console` vía `HTTPRoute nexus.mindtechpy.net` → `nexus-console:8080` (`parentRefs: public-gateway` ns `traefik`). nginx hace proxy **únicamente** de `/api/admin/` y `/api/v1/webhooks/` a `nexus-api:8080`. El runtime m2m (`/api/v1/*` resto) no tiene ruta pública. DNS: el CNAME `nexus.mindtechpy.net` → túnel ya existe (lo creó el usuario). La regla wildcard del túnel (`*.mindtechpy.net → http://traefik.traefik:80`) ya lo cubre: no hace falta public hostname nuevo, solo el `HTTPRoute`.
- **URLs que usa Nexus** (desde `connections.base_url`, allowlist por defecto `*.svc.cluster.local`):
  - POS: `http://backend.punto-venta.svc.cluster.local:3000/api`
  - SIFEN: `http://backend.sifen.svc.cluster.local:8081`
  - GiftHub: `http://api.comercio-central.svc.cluster.local:3000` (verificado en `gift-hub/k8s/api.yaml:142-152`). En sentido inverso, el transporte de webhooks de GiftHub **exige `https://` y bloquea IPs privadas** (`https-webhook-transport.ts:26-29`): la URL registrada en GiftHub es la pública `https://nexus.mindtechpy.net/api/v1/webhooks/gifthub/{connectionId}`.
- **NetworkPolicies** (slice S7): `nexus` default-deny con egress a kube-dns (53), `punto-venta`, `sifen`, `comercio-central`, `postgres` propio, y 443 para el CronJob de backup; ingress desde `traefik` (solo console) y desde `comercio-central` (webhooks) y `punto-venta` (runtime). En los repos de POS y SIFEN se agrega `namespaceSelector: kubernetes.io/metadata.name: nexus` a sus NetworkPolicies de ingress al `backend`.
- **Observabilidad**: `ServiceMonitor` sobre el puerto `management` `/actuator/prometheus` (Prometheus levanta cualquier ServiceMonitor del cluster); logs JSON a stdout (Promtail → Loki). No hay collector OTel en el cluster: traces fuera de v1.
- **Backups**: CronJob copiado de `punto-venta/k8s/backup-cronjobs.yaml` (pg_dump `-Fc`, verificación de tamaño > 10 KB, rclone crypt a R2 bajo prefijo `nexus`); agregar `nexus` a `BACKUP_NAMESPACES` en `platforms-deploy.yml`.

## 5. Seguridad e identidades

### 5.1 Máquina a máquina (POS → Nexus, y futuros)
- `POST /oauth/token` con `grant_type=client_credentials`, HTTP Basic `client_id:client_secret`. Devuelve JWT **HS256** (secreto `NEXUS_JWT_SIGNING_KEY`, 256 bits) con claims `sub=client_id`, `tenant_id`, `scope`, `typ=client`, exp 15 min. Spring Security OAuth2 Resource Server valida con el mismo secreto (`JwtDecoder` local). Sin Authorization Server.
- Tabla `api_clients`: secreto con bcrypt (cost 12), scopes por cliente, estado, `last_used_at`, rotación con `secret_previous_hash` y gracia 24 h.
- Scopes v1: `fiscal:issue`, `fiscal:read`, `orders:write`, `orders:read`, `catalog:read`, `operations:read`.
- El `tenant_id` sale **siempre** del token; cualquier `tenantId` en body/path se ignora o se rechaza con 403 si no coincide.

### 5.2 Usuarios de la consola
- `POST /api/admin/auth/login` (email + password) → access JWT 15 min (`typ=user`, `role`, `tenant_id` nullable para `platform_admin`) + refresh token opaco en cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/admin/auth`, persistido con hash en `user_sessions` (8 h, revocable). `POST /refresh`, `POST /logout`, `GET /me`.
- Password bcrypt cost 12, mínimo 12 caracteres, bloqueo progresivo tras 5 fallos (`users.failed_attempts`, `locked_until`). Rate limit 10/min por IP en login.
- Bootstrap: si no hay usuarios, se crea `platform_admin` con `NEXUS_BOOTSTRAP_ADMIN_EMAIL` / `NEXUS_BOOTSTRAP_ADMIN_PASSWORD` (del Secret) y se obliga cambio al primer login.
- Roles y permisos:

| Rol | Alcance | Puede |
|---|---|---|
| `platform_admin` | todos los tenants | todo, incluido crear tenants, usuarios y `EMERGENCY_STOP` |
| `tenant_admin` | su tenant | clientes m2m, conexiones (crear/editar/probar), integraciones (estados salvo emergency), replay DLQ |
| `operator` | su tenant | ver todo, `PAUSED/ENABLED/DRAINING`, replay DLQ, ejecutar sync de catálogo |
| `auditor` | su tenant | ver todo incl. auditoría, sin acciones |
| `viewer` | su tenant | ver dashboard y operaciones |

- `IdentityProviderPort { Optional<AuthenticatedUser> authenticate(Credentials) }` con impl local; OIDC es otra impl (Evolución).

### 5.3 Secretos y credenciales de conexión
- Secret K8s `nexus-secrets` (creado por CI desde GitHub Secrets, mismos nombres que el `.env` local gitignored): `NEXUS_DB_NAME`, `NEXUS_DB_USER`, `NEXUS_DB_PASSWORD`, `NEXUS_MASTER_KEY` (32 bytes base64), `NEXUS_JWT_SIGNING_KEY`, `NEXUS_BOOTSTRAP_ADMIN_EMAIL`, `NEXUS_BOOTSTRAP_ADMIN_PASSWORD`. El Deployment de Postgres toma `POSTGRES_DB/USER/PASSWORD` del mismo Secret.
- `connections.credentials_enc`: JSON de credenciales (`accessKey`+`secretKey` SIFEN; `apiKey` GiftHub; `apiKey` POS) cifrado **AES-256-GCM** con `NEXUS_MASTER_KEY`, nonce de 12 bytes aleatorio por registro, AAD = `tenant_id:connection_id`. La consola nunca devuelve el valor; "probar conexión" ejecuta un `GET` inocuo del conector. Rotación: editar credencial = nuevo cifrado + audit.
- Logging con masking (`Authorization`, `X-Secret-Key`, `X-Signature`, `apiKey`, `secret`) vía `MaskingJsonLayout`; nunca se loguean cuerpos de credenciales ni PII completa (teléfono se trunca a los últimos 4 dígitos).

### 5.4 SSRF y entrada
- `base_url` de conexión: scheme `http|https`, host en allowlist configurable `nexus.connectors.allowed-hosts` (default: `*.svc.cluster.local`, `localhost` solo en perfil `local`), sin IPs literales, sin credenciales en URL, redirects deshabilitados en `RestClient`.
- Webhooks entrantes: verificación HMAC con tolerancia ±300 s, dedupe por id de entrega (`inbound_events`), body máximo 256 KB, rate limit por `connectionId`.
- Errores: `application/problem+json` (RFC 9457) con `type` estable `https://nexus.mindtechpy.net/problems/<code>`, `code`, `correlationId`.

## 6. Modelo de datos (PostgreSQL, Flyway)

Todas las tablas de negocio llevan `tenant_id uuid NOT NULL` y `created_at/updated_at timestamptz`. Sin RLS en v1 (todas las queries pasan por repositorios que exigen `tenantId`; test de IDOR en S1).

| Tabla | Columnas clave | Constraints |
|---|---|---|
| `tenants` | `id, slug, name, status(ACTIVE\|SUSPENDED)` | `UNIQUE(slug)` |
| `users` | `id, tenant_id NULL, email, password_hash, role, status, must_change_password, failed_attempts, locked_until` | `UNIQUE(lower(email))` |
| `user_sessions` | `id, user_id, refresh_hash, expires_at, revoked_at, ip, user_agent` | idx `(user_id)` |
| `api_clients` | `id, tenant_id, client_id, secret_hash, secret_previous_hash, secret_rotated_at, scopes text[], status, last_used_at` | `UNIQUE(client_id)` |
| `connections` | `id, tenant_id, connector(SIFEN\|POS\|GIFTHUB), name, base_url, credentials_enc bytea, settings jsonb, status, last_test_at, last_test_ok` | `UNIQUE(tenant_id, connector, name)` |
| `integrations` | `id, tenant_id, flow(FISCAL\|ORDER\|CATALOG), code, source_connection_id, target_connection_id, state, policy jsonb, version int` | `UNIQUE(tenant_id, code)` |
| `operations` | `id, tenant_id, integration_id, flow, kind(ISSUE_INVOICE\|CANCEL_INVOICE\|CREATE_ORDER\|ORDER_STATUS\|CATALOG_SYNC), idempotency_key, request_hash, status, current_step, attempts, max_attempts, next_run_at, locked_by, locked_until, priority smallint, correlation_id, payload jsonb, result jsonb, last_error jsonb, dead_reason, finished_at` | `UNIQUE(tenant_id, kind, idempotency_key)`; idx `(status, next_run_at)`; idx `(tenant_id, created_at desc)` |
| `operation_steps` | `id, operation_id, step, attempt, status, external_ref, request_hash, response_code, error_code, started_at, finished_at` | idx `(operation_id)` |
| `entity_mappings` | `id, tenant_id, connection_id, entity_type(CUSTOMER\|PRODUCT\|ORDER\|INVOICE), internal_id, external_id` | `UNIQUE(tenant_id, connection_id, entity_type, internal_id)`, `UNIQUE(tenant_id, connection_id, entity_type, external_id)` |
| `inbound_events` | `id, tenant_id, connection_id, source(GIFTHUB\|SIFEN), event_id, event_type, received_at, operation_id NULL` | `UNIQUE(connection_id, event_id)` |
| `audit_events` | `id, tenant_id NULL, actor_type(USER\|CLIENT\|SYSTEM), actor_id, action, resource_type, resource_id, before jsonb, after jsonb, correlation_id, happened_at` | idx `(tenant_id, happened_at desc)` |

`policy` de integración (jsonb tipado en `IntegrationPolicy`): `{ "ratePerSecond": 2, "burst": 5, "maxInFlight": 2, "maxAttempts": 5, "baseDelaySeconds": 5, "maxDelaySeconds": 300, "readTimeoutMs": 150000, "scheduleEverySeconds": 300 }` (el último solo para `CATALOG`). `readTimeoutMs` máximo 540 000: por debajo del lease de 10 min de la cola.

Retención: `operations.payload` de operaciones `SUCCEEDED` se vacía a `null` 30 días después de `finished_at` (job diario), `result` queda; `FAILED` y `DEAD` conservan el payload para poder reprocesarlas. `inbound_events` 90 días.

## 7. Motor de operaciones durable (solo Postgres)

### 7.1 Ciclo de vida
```
PENDING ──claim──► RUNNING ──ok──► SUCCEEDED
   ▲                 │ error retryable        │ error no retryable / agotado
   └── RETRY(next_run_at) ◄┘                  ▼
                                            FAILED  (resultado definitivo de negocio, p.ej. RECHAZADO)
                                            DEAD    (requiere humano: credenciales, conflicto idempotente, agotado)
DEAD ──replay (consola, mismo idempotency_key, attempts=0)──► PENDING
```

### 7.2 Claim (worker, cada 500 ms, `nexus.worker.batch-size=10`)
```sql
WITH next AS (
  SELECT id FROM operations
  WHERE status IN ('PENDING','RETRY') AND next_run_at <= now()
  ORDER BY priority, next_run_at
  LIMIT :n FOR UPDATE SKIP LOCKED)
UPDATE operations o SET status='RUNNING', locked_by=:worker, locked_until=now()+interval '10 minutes',
       attempts=attempts+1, updated_at=now()
FROM next WHERE o.id=next.id RETURNING o.*;
```
Un job de rescate (cada minuto) devuelve a `RETRY` las `RUNNING` con `locked_until < now()` (worker caído). La transacción del claim se cierra **antes** de la llamada HTTP; cada paso persiste su resultado en su propia transacción.

### 7.3 Gates antes de cada paso
1. Estado de la integración (lectura fresca, sin caché): `ENABLED` ejecuta; `PAUSED` → re-encola sin contar intento; `DRAINING` → ejecuta solo operaciones ya `RUNNING`/`RETRY`, rechaza nuevas en la API con 409 `integration-draining`; `DISABLED` y `EMERGENCY_STOP` → no despacha, nuevas con 409. Al salir de `PAUSED/EMERGENCY_STOP` se reanuda sin replay manual.
2. `RateLimiterPort.tryAcquire(integrationId)` (token bucket) y `maxInFlight` (semáforo). Si no hay cupo → `RETRY` con +1 s sin contar intento. **Techo documentado:** ambos en memoria, válidos para 1 réplica; `RateLimiterPort` se reimplementa en Postgres/Redis si hay 2+ réplicas.
3. Precondiciones de credenciales: la conexión debe tener `credentials_enc` descifrable antes del primer efecto; si no → `DEAD(credentials-missing)`.

### 7.4 Retries y clasificación de errores
Backoff `min(maxDelay, base * 2^(attempt-1))` ± 20 % jitter. Clasificación común a conectores (`ConnectorError.kind`):

| kind | Ejemplos | Acción |
|---|---|---|
| `TRANSIENT` | timeout connect, 502/503/504, I/O | `RETRY` |
| `RATE_LIMITED` | 429 | `RETRY` respetando `Retry-After` |
| `UNCERTAIN` | timeout de lectura tras enviar, 524 | paso `VERIFY` antes de reintentar |
| `REJECTED` | 400/422 validación, `BUSINESS_RULE` | `FAILED` con detalle (no reintenta) |
| `CONFLICT` | 409 `IDEMPOTENCY_CONFLICT` | `DEAD` |
| `IN_PROGRESS` | 409 `EMISION_EN_CURSO` | `RETRY` a 30 s, no cuenta intento |
| `AUTH` | 401/403 | `DEAD(credentials)` + alerta |
| `PLAN_LIMIT` | 402 `PLAN_LIMIT_EXCEEDED` / `WORKSPACE_SUSPENDED` (SIFEN) | `DEAD(plan-limit)`; replay tras regularizar el plan |

### 7.5 API de operaciones
`GET /api/v1/operations/{id}` (m2m, mismo tenant) y `/api/admin/operations?tenantId&flow&status&from&to&q` + `/{id}` (con steps) + `POST /{id}/replay` (solo `DEAD`, audit obligatorio).

## 8. Contratos canónicos v1 (runtime `/api/v1`)

Convenciones: JSON, `X-Correlation-Id` (se genera si falta y se devuelve), dinero en entero PYG (`long`) salvo `quantity` decimal, instantes ISO-8601 con offset, `202` solo cuando el job quedó persistido.

### 8.1 Fiscal
```http
POST /api/v1/invoices                         scope fiscal:issue
GET  /api/v1/invoices/{sourceRef}             scope fiscal:read
POST /api/v1/invoices/{sourceRef}/cancel      scope fiscal:issue   {"reason": "..."}
GET  /api/v1/invoices/{sourceRef}/kude?template=ticket|full   scope fiscal:read  → application/pdf
```
`sourceRef` es el id estable de la venta en el origen (uuid del POS); es la clave idempotente y el `transactionId` que viaja a SIFEN (≤ 100 chars, `[A-Za-z0-9:_-]`).

Request `POST /api/v1/invoices` (alineado 1:1 con `FacturarTransaccionRequest` de SIFEN para que el mapper sea trivial; `emisor` nunca se envía, lo pone SIFEN desde la credencial):
```json
{
  "sourceRef": "7c0f…-uuid-de-la-venta",
  "documentType": "INVOICE | CREDIT_NOTE | DEBIT_NOTE",
  "issuedAt": "2026-10-10T14:05:00-03:00",
  "branchRef": "SUC-001", "pointOfSaleRef": "CAJA-01",
  "referenceCdc": null, "issueReasonCode": null,
  "header": { "currency": "PYG", "paymentCondition": "CASH | CREDIT", "paymentMethod": "EFECTIVO" },
  "customer": { "documentType": "RUC | CI | INNOMINADO", "documentNumber": "80012345-6", "name": "…", "email": null },
  "items": [ { "code": "PAN-001", "description": "Chipa", "quantity": "4", "unitPrice": 1100, "discount": 0, "vatRate": "10", "unit": "UNI" } ],
  "globalDiscount": 0
}
```
Response `202`:
```json
{ "operationId": "…", "sourceRef": "…", "status": "PENDING" }
```
`GET /api/v1/invoices/{sourceRef}` → `200`:
```json
{ "sourceRef": "…", "operationId": "…", "status": "PENDING | SUBMITTED | APPROVED | APPROVED_WITH_NOTES | REJECTED | CANCELLED | FAILED | DEAD",
  "cdc": "0180…", "documentNumber": "001-001-0000123", "providerStatus": "APROBADO", "providerCode": "0260", "providerMessage": "…",
  "total": 9100, "updatedAt": "…", "error": null }
```
Mapeo de `providerStatus` → `status`: `EN_PROCESO/PENDIENTE_ENVIO → SUBMITTED`, `APROBADO → APPROVED`, `APROBADO_CON_OBSERVACION → APPROVED_WITH_NOTES`, `RECHAZADO → REJECTED`, `CANCELADO → CANCELLED`.

### 8.2 Pedidos y catálogo
```http
POST /api/v1/webhooks/gifthub/{connectionId}    firma GiftHub; eventos order.created|order.cancelled|order.paid|ping
POST /api/v1/webhooks/sifen/{connectionId}      firma SIFEN;  eventos APROBADO|RECHAZADO|CANCELADO|PENDIENTE_ENVIO
POST /api/v1/orders/{channel}/{externalRef}/status   scope orders:write (lo llama el POS)
     {"status": "COMPLETED | CANCELLED", "saleNumber": "SALE-00000042", "paidAmount": 45000, "paymentMethod": "CASH", "occurredAt": "…"}
GET  /api/v1/orders/{channel}/{externalRef}          scope orders:read
POST /api/admin/integrations/{id}/run                (CATALOG) ejecuta sync ahora
```

## 9. Flujo fiscal `[NEXUS-FISCAL]`

```
POS                      Nexus API                    Worker                              SIFEN
 │ POST /invoices ──────► validar JWT/scope/tenant
 │                        integración FISCAL ENABLED? conexión SIFEN con credenciales? (fail-fast)
 │                        startOrFind(kind=ISSUE_INVOICE, key=sourceRef, hash)
 │ ◄── 202 {operationId}  ──── mismo hash: 200 estado actual / otro hash: 409 idempotency-conflict
 │                                                     claim ──► SUBMIT  POST /api/v1/facturas (HMAC) ──► síncrono ≤150 s
 │                                                              200 → guarda cdc/estado → ¿final? SUCCEEDED/FAILED : TRACK
 │                                                              UNCERTAIN → VERIFY GET /facturas/{tx} → 200 adopta / 404 re-SUBMIT
 │                                                     TRACK: poll 1m,5m,15m,1h,6h (máx 72 h) o webhook SIFEN → final
 │ GET /invoices/{ref} (polling del POS, 60 s) ◄─────── estado persistido
```

- **Pasos**: `SUBMIT` → `VERIFY` (solo tras `UNCERTAIN`) → `TRACK` (solo si `SUBMITTED`). Gate de orden: `TRACK` no corre sin `cdc` o sin `SUBMIT` confirmado.
- **Validar el efecto real**: `SUBMIT` solo se considera exitoso si la respuesta trae `cdc` no vacío y `estado` ∈ enum conocido; cualquier otra forma → `UNCERTAIN`.
- **Idempotencia remota**: SIFEN dedupe por `(emisor, transactionId)` + hash; un 200 repetido es éxito; `409 EMISION_EN_CURSO` es "esperar", no fallo; `409 IDEMPOTENCY_CONFLICT` es `DEAD` (alguien cambió el payload: revisión humana, nunca re-emitir con otro `transactionId`).
- **Compensación**: un DE aprobado **no se compensa automáticamente** (requiere evento fiscal de cancelación con ventana legal de 48 h, decisión humana). La única función de compensación de la saga, `FiscalSaga.compensate(op, cause)`, hace exactamente: marcar `DEAD`/`FAILED`, registrar `operation_steps`, emitir audit y métrica. Sin `return` temprano. La cancelación es una operación de negocio separada (`CANCEL_INVOICE`, pasos `SUBMIT_CANCEL` → `VERIFY`), disparada por el POS.
- **Timeouts**: connect 5 s, read `policy.readTimeoutMs` (default 150 000, SIFEN pide >120 s). Llamada por ClusterIP: no pasa por Cloudflare (sin 524).
- **Credenciales SIFEN**: una API key por emisor = por tenant; `hmacRequired` siempre en producción. Nexus firma `METHOD\nPATH?QUERY\nTS\nNONCE\nhex(body)` con `X-Timestamp`, `X-Nonce`, `X-Signature` (vectores de prueba en `electronico-sifen-backend/docs/INTEGRACION_API.md:127-139`). La IP allowlist de la key queda vacía (tráfico interno) o con el CIDR de pods.
- **KuDE y cancelación**: pass-through síncrono con timeouts cortos (10 s); el PDF no se persiste.
- **Logs**: `[NEXUS-FISCAL] op=… tenant=… step=SUBMIT attempt=2 → 200 estado=APROBADO cdc=…` en inicio, respuesta, decisión y compensación.

## 10. Flujos de pedidos y catálogo

### 10.1 `[NEXUS-ORDER]` GiftHub → POS
```
GiftHub ── webhook order.created (HMAC, X-GiftHub-Delivery) ──► Nexus /webhooks/gifthub/{conn}
   verifica firma (±300 s) · dedupe (connection_id, delivery_id) · integración ORDER ENABLED · conexión POS con credenciales
   startOrFind(kind=CREATE_ORDER, key=order.id) ─► 202
Worker:
   1 RESOLVE_CUSTOMER  POS POST /api/integration/customers/resolve {phoneE164, name}  (si el evento no trae cliente → customer por defecto de la conexión)
   2 CREATE_ORDER      POS POST /api/integration/orders  Idempotency-Key=GIFTHUB:{order.id}
                        {channel:"GIFTHUB", externalRef, customerId, branchRef, warehouseRef, items[{code, quantity}], delivery{…}, paymentMethod, notes}
                        201 {saleId, saleNumber, total, status:"PENDING"}  · 422 insufficient-stock|unknown-product → compensar
                        UNCERTAIN → VERIFY GET /api/integration/orders/GIFTHUB/{externalRef} → 200 adopta / 404 reintenta
   3 NOTIFY_GIFTHUB    GiftHub POST /api/integrations/v1/orders/{id}/status {status:"CONFIRMED", note:"POS SALE-00000042"}
Compensación (única función OrderSaga.compensate):
   - si CREATE_ORDER fue rechazado por el POS → GiftHub status CANCELLED con nota del motivo → op FAILED
   - si CREATE_ORDER tuvo éxito y NOTIFY agota reintentos → op DEAD (la venta existe; replay solo reejecuta NOTIFY porque el step 2 está SUCCEEDED)
   - nunca cancela en el POS una venta que ya salió de PENDING
```
Precio y stock los decide el POS: Nexus envía `code` y `quantity`; el POS valida existencia, `sellable`, stock en el depósito y calcula totales. Sin reserva de stock en v1 (el POS descuenta al completar, igual que sus propias ventas `PENDING`); overselling entre pedido y cobro es un riesgo aceptado y documentado (Evolución: reserva).

**Estados de vuelta (POS → GiftHub)**: el POS, en `sale.completed` / `sale.cancelled` de ventas con `channel='GIFTHUB'`, llama `POST /api/v1/orders/GIFTHUB/{externalRef}/status`; Nexus crea `ORDER_STATUS` (key `{externalRef}:{status}`) con pasos `MARK_PAID` (`POST /orders/{id}/paid {reference: saleNumber, method}`) → `SET_STATUS` (`DELIVERED` o `CANCELLED`). Ambos idempotentes del lado GiftHub.

### 10.2 `[NEXUS-CATALOG]` POS → GiftHub
Operación `CATALOG_SYNC` creada por el scheduler (cada `scheduleEverySeconds`, key `{integrationId}:{yyyyMMddHHmm}`) o por `run` manual; `maxInFlight=1`. Pasos: `FETCH_POS` (paginado `GET /api/integration/catalog?warehouseRef&page&limit=500`, acumula en `payload`) → `PUSH_GIFTHUB` (`PUT /api/integrations/v1/products/bulk` en lotes de ≤500, `dry_run` configurable en la conexión para pruebas). Mapeo: `external_id=code`, `name`, `price_pyg=price`, `available = active && sellable && (stock>0 || !tracked)`, `stock_qty`, `category`, `image_url`. Sin compensación (upsert idempotente); fallo → retry; resultado con conteos en `result`. Catálogo completo en cada corrida (el POS no actualiza `updatedAt` al mover stock); techo práctico ~5 000 productos por tenant.

## 11. Cambios en los sistemas integrados

### 11.1 punto-venta (slice S4) — retiro del adaptador viejo y nuevo cliente Nexus
Decisión (ADR-004): **eliminación directa en un solo PR**, sin flag de doble ruta, porque en prod `ELECTRONIC_INVOICE_ENABLED=false`, 0 timbrados y 0 clientes; `ELECTRONIC_INVOICE_ENABLED` se conserva como único interruptor. Rollback = revert del commit.

Se elimina: `electronic-invoice-api.service.ts`, `sifen-hmac.util.ts`, `sifen.controller.ts` y sus 19 proxies, `electronic-invoice-sync.service.ts` (polling por `setInterval`), `docs/integracion-factura-electronica/swagger.yml`, tests asociados, env `ELECTRONIC_INVOICE_FALLBACK_TO_LEGACY/_SYNC_*`.
Se agrega: `modules/nexus/` (`NexusClient` con token client_credentials cacheado, `NexusFiscalAdapter implements FiscalIntegrationPort`, `NexusFiscalSyncJob` BullMQ repeatable cada 60 s que hace `GET /invoices/{sourceRef}` para facturas no finales), migración que renombra `fiscal_credentials.access_key_enc/secret_key_enc` → `client_id/client_secret_enc` y elimina `emisor_id`, `environment`; `NEXUS_BASE_URL` global por env (ClusterIP). `transactionId` pasa a ser `sale.id` (uuid, globalmente único: cierra el bug del unique global). El estado persistido en `invoices.external_*` se mantiene (misma semántica, origen Nexus).

Nuevo, para pedidos y catálogo: tabla `integration_api_keys` + `IntegrationApiKeyGuard` (`X-Api-Key: pv_live_<prefix>_<secret>`, hash SHA-256, scopes `catalog:read orders:write customers:write`, `system_user_id`, `default_branch_id`, `default_warehouse_id`); endpoints `/api/integration/catalog`, `/api/integration/customers/resolve`, `/api/integration/orders` (+ `GET`/`cancel` por `{channel}/{externalRef}`); columnas `sales.channel`, `sales.external_ref` con `UNIQUE(organization_id, channel, external_ref)`; `customers.phone_e164` con `UNIQUE(organization_id, phone_e164)` parcial; listener `NexusOrderStatusListener` para ventas de canal.

### 11.2 gift-hub (slice S5)
Única modificación: `Order.externalOrderId` + `externalOrderNumber` (migración Prisma) y que `POST /api/integrations/v1/orders/:id/status` acepte `external_ref` y `external_number` opcionales para guardarlos y mostrarlos en el panel. Todo lo demás se usa tal cual (API key `gh_live_*`, bulk de productos, webhooks).

## 12. Consola Angular (`nexus-console`)
Angular 22.2, standalone components, signals, `provideHttpClient` con interceptor Bearer + refresh, `@angular/router` con guards por rol, Angular Material o CDK mínimo (decisión del slice; sin librería de UI pesada). Pantallas: Login · Overview (operaciones por estado/flujo 24 h, DLQ, integraciones por estado) · Tenants (platform_admin) · Usuarios · Clientes m2m (crear, ver secreto una vez, rotar, revocar) · Conexiones (crear/editar/probar; credenciales write-only) · Integraciones (crear, política, cambiar estado con confirmación y motivo) · Operaciones (lista filtrable, detalle con steps y error sanitizado, replay) · Auditoría. Build en nginx-unprivileged 8080, proxy `/api/admin/` y `/api/v1/webhooks/` → `nexus-api:8080`.

## 13. Observabilidad
Micrometer → `/actuator/prometheus`: `nexus_operations_total{flow,kind,status}`, `nexus_operation_duration_seconds{flow,kind}`, `nexus_step_duration_seconds{flow,step}`, `nexus_connector_latency_seconds{connector,operation,outcome}`, `nexus_queue_depth{status}`, `nexus_dead_letters_total{flow}`, `nexus_rate_limited_total{flow}`. Sin `tenant_id` como label. Logs JSON (logback + `logstash-logback-encoder`) con `correlationId`, `tenantId`, `operationId`, `step`, prefijo de flujo. Alertas (PrometheusRule en `k8s/`): DLQ > 0 por 15 min, operaciones `SUBMITTED` > 24 h, errores `AUTH`, readiness caída.

## 14. Pruebas
- **ArchUnit**: reglas de capas (falla el build).
- **Unit** (sin Spring): mappers canónico↔SIFEN/POS/GiftHub, `IntegrationPolicy`, backoff, clasificación de errores, verificadores HMAC con vectores de SIFEN y de GiftHub, cifrado AES-GCM, transiciones de estado de operación e integración.
- **Integration** (Testcontainers Postgres 16 + WireMock por conector): claim concurrente sin duplicados (2 workers en el mismo test), rescate de lease vencido, idempotencia (`startOrFind` mismo/distinto hash), saga fiscal por ramas: 200 aprobado, 200 pendiente+track, timeout→verify 200, timeout→verify 404→resubmit, 409 en curso, 409 conflicto→DEAD, 401→DEAD, 422→FAILED; saga pedido por ramas: éxito, 422 stock→compensa CANCELLED en GiftHub, timeout→verify, notify agotado→DEAD y replay; webhook GiftHub: firma inválida 401, duplicado 200 sin nueva operación; integración `PAUSED/DISABLED/EMERGENCY_STOP`; IDOR cross-tenant en runtime y admin.
- **Contract**: fixtures reales anonimizados de `electronico-sifen-backend/examples/*.json` y `gift-hub/docs/manual-integracion.md`.
- **E2E local** (S7): `compose --profile e2e` con POS real, GiftHub real y WireMock-SIFEN (o el sifen-backend real del stack local `electronico-sifen/.local-stack`, al que ya se conecta `local-stack/punto-venta.override.yml`); script `scripts/e2e.sh` que crea tenant/cliente/conexiones por admin API, dispara una venta en POS y verifica `APPROVED`, y un pedido simulado de GiftHub que termina `CONFIRMED` con `saleNumber`.

## 15. CI/CD y despliegue
- `ci.yml` (PR, `ubuntu-latest`): `mvn -B verify` (Testcontainers con Docker del runner) y `npm ci && npm test && npm run build` en console.
- `deploy.yml` (push a `main`, `runs-on: self-hosted`, `permissions: contents: write`): build & push `ghcr.io/poravv/mindtech-nexus/api:sha-<7>` y `/console:sha-<7>` (paquetes públicos); `kubectl create secret generic nexus-secrets --from-file=… --dry-run=client -o yaml | kubectl apply -n nexus -f -`; `sed` del tag en `k8s/api-deployment.yaml` y `k8s/console-deployment.yaml`; commit `chore(deploy): nexus sha-<7> [skip ci]` como `github-actions[bot]` con reintento `fetch`+`reset --hard`; espera `rollout status` hasta 10 min; smoke `wget` a `/actuator/health` desde el pod de console.
- Flyway corre al arrancar `nexus-api` (migraciones aditivas; expand/contract para cambios destructivos). Backup previo a migración: Job hook de Argo `PreSync` copiado de accenova (Evolución si no entra en S7).
- Pasos manuales de infraestructura (una vez): CNAME en Cloudflare; PR en `k8s-platforms` (`argocd/apps/nexus.yaml`, `BACKUP_NAMESPACES`); PRs en `punto-venta/k8s/network-policy.yaml` y `electronico-sifen-backend/k8s/network-policy.yaml`; en SIFEN crear emisor + API key del tenant piloto y registrar el webhook `https://nexus.mindtechpy.net/api/v1/webhooks/sifen/{connectionId}` (SUPER_ADMIN); en GiftHub crear integración + API key + webhook `…/webhooks/gifthub/{connectionId}` con `includeCustomer` activo.

## 16. Evolución (fuera del MVP, en orden probable)
1. `RateLimiterPort` en Postgres o Redis y `JobQueuePort` en RabbitMQ cuando haya >1 réplica de worker o >50 ops/s.
2. OIDC (Keycloak u otro) detrás de `IdentityProviderPort`; RLS en Postgres como defensa en profundidad.
3. Reserva de stock en el POS para pedidos de canal; estados intermedios `PREPARING/READY` desde el POS.
4. Webhook saliente de Nexus hacia el POS (reemplaza el polling de 60 s); outbox en el POS para `sale.completed`.
5. Sync de catálogo incremental por watermark; imágenes vía MinIO → GiftHub.
6. Traces OTel cuando el cluster tenga collector; multi-réplica y PodDisruptionBudget.
7. Editor de mapeos guiado, workflow templates, conectores externos (Bancard, Accenova, OBRANOVA), planes comerciales y metering: solo con demanda real.

## 17. Riesgos y decisiones con default
| Riesgo / pregunta | Default adoptado |
|---|---|
| SIFEN nunca emitió contra sifen-test real (`PLAN_FASES.md:163-175`) | Nexus se prueba contra WireMock con fixtures de `examples/`; la primera emisión real en sifen-test se hace con el tenant piloto y `ambiente=2` antes de cualquier producción |
| GiftHub exige `https` y bloquea IPs privadas al enviar webhooks (verificado) | La URL registrada es la pública `https://nexus.mindtechpy.net/api/v1/webhooks/gifthub/{connectionId}` (firma HMAC + dedupe + rate limit) |
| El POS no reserva stock | Aceptado en v1; el POS rechaza al crear si no hay stock; GiftHub recibe `CANCELLED` con motivo |
| Cliente sin teléfono/`includeCustomer` desactivado | La conexión POS tiene `settings.defaultCustomerId` ("Consumidor final"); se usa si el evento no trae cliente |
| Sucursal/depósito/caja para pedidos de canal | `settings.branchRef`/`warehouseRef` en la conexión POS; la venta `PENDING` no necesita caja abierta; el cobro lo hace un operador humano en el POS |
| Argon2 vs bcrypt | bcrypt cost 12 (sin dependencia extra); cambiar después es trivial por `PasswordEncoder` delegante |
