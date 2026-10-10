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
- `POST /api/admin/connections/{id}/test` hace una lectura autenticada y sin efectos (POS `GET /api/integration/catalog?page=1&limit=1`, GiftHub `GET /api/integrations/v1/products?limit=1`, SIFEN `GET /api/v1/facturas?page=0&size=1` firmado con HMAC), revalidando antes la base URL contra la allowlist; responde solo `ok`/`detail`, nunca credenciales ni el cuerpo remoto.
- `/api/admin/integrations` (`GET`, `POST`, `GET/PUT /{id}`, `POST /{id}/state {state, reason}`): sin `DELETE`, `DISABLED` retira una integración conservando su historial. `EMERGENCY_STOP` (entrar y salir) solo `platform_admin`; `DISABLED` requiere `tenant_admin`; `operator` alterna `ENABLED/PAUSED/DRAINING`.
- `/api/admin/operations?flow&status&from&to&q&page&size`, `/summary?from` (por estado/flujo + tamaño de la DLQ), `/{id}` (con `steps`), `POST /{id}/replay` (solo `DEAD`, auditado), `POST /{id}/discard` (solo `DEAD` → `DISCARDED` terminal: sale de la DLQ sin reprocesar, auditado).
- `GET /api/v1/operations/{id}` — runtime m2m, scope `operations:read`, solo operaciones del tenant del token.
- OpenAPI (solo perfil `local`): `/v3/api-docs`, `/swagger-ui.html`. Management (puerto 8081): `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`.

### Motor de operaciones (S2)

- **Cola**: tabla `operations`. El worker (`nexus.worker.enabled`) reclama cada 500 ms hasta `batch-size` (10) operaciones con `FOR UPDATE SKIP LOCKED`, lease de 10 min; un job por minuto devuelve a `RETRY` los leases vencidos. Cada paso corre fuera de transacción y su resultado se guarda con compare-and-set sobre el lease (un worker rescatado no pisa al nuevo). Apagado ordenado: deja de reclamar y espera `shutdown-timeout` (25 s) a los pasos en curso.
- **Idempotencia**: `UNIQUE(tenant_id, kind, idempotency_key)` + SHA-256 canónico del payload: mismo hash → la operación existente; distinto → 409 `idempotency-conflict`.
- **Gates por paso**: estado de la integración (`ENABLED`/`DRAINING` despachan; `PAUSED` retiene sin gastar intentos; `DISABLED`/`EMERGENCY_STOP` no despachan; nuevas solo con `ENABLED`/`PAUSED`, si no 409), credenciales descifrables (si no `DEAD credentials-missing`), intentos, rate limit y `maxInFlight` (sin cupo → `RETRY` +1 s sin gastar intento).
- **Errores** (`ErrorKind`): `TRANSIENT`/`RATE_LIMITED`/`UNCERTAIN` → `RETRY` con backoff `min(max, base·2^(n-1))` ±20 % (o `Retry-After`) hasta `DEAD attempts-exhausted`; `IN_PROGRESS` → +30 s sin gastar intento; `REJECTED` → `FAILED`; `CONFLICT`/`AUTH` → `DEAD`.
- **Backpressure**: más de `nexus.queue.max-unfinished-per-tenant` (1000) operaciones vivas → 429 `queue-saturated` con `Retry-After: 30`; base caída → 503 con `Retry-After`. Nunca 202 sin persistir.
- **Techo**: rate limit y concurrencia en memoria, válidos con **1 réplica** (ADR-002). Con 2+ réplicas reimplementar `RateLimiterPort` en Postgres/Redis.
- **Métricas** (`/actuator/prometheus`): `nexus_operations_total{flow,kind,status}`, `nexus_operation_duration_seconds`, `nexus_step_duration_seconds{flow,step}`, `nexus_queue_depth{status}`, `nexus_dead_letters_total{flow}`, `nexus_rate_limited_total{flow}`; sin `tenant_id`.
- **Enchufar un flujo (S3/S5)**: implementar `application.saga.Saga` (`kind()`, `firstStep()`, `execute(StepContext) → StepResult` = `Completed` | `Next(step, delay)` | `Failed(ConnectorError)`, y opcional `compensate`) y declararlo como bean; crear operaciones con `StartOperation`.

### Flujo fiscal SIFEN (S3)

- **Alta**: conexión `SIFEN` con `baseUrl` interna (`http://backend.sifen.svc.cluster.local:8081`) y credenciales `{accessKey, secretKey, webhookSecret}` (cifradas; `webhookSecret` es el `secret` del webhook registrado en SIFEN); una única integración `FISCAL` por tenant con esa conexión como destino. Cliente m2m del POS con scopes `fiscal:issue fiscal:read operations:read`.
- **Runtime** (contrato del POS, spec §8.1): `POST /api/v1/invoices` (202 `{operationId, sourceRef, status}`; mismo `sourceRef` y payload → 200 con el estado; otro payload → 409), `GET /api/v1/invoices/{sourceRef}`, `POST /api/v1/invoices/{sourceRef}/cancel {reason}` (202 `{operationId}`; seguir con `GET /api/v1/operations/{id}` → `status`, `lastError.message`, `deadReason`), `GET /api/v1/invoices/{sourceRef}/kude?template=ticket|full` (PDF de SIFEN, no se guarda).
- **Mapeo a SIFEN**: `sourceRef` → `transactionId`; `branchRef` → `sucursalId`; `pointOfSaleRef` → `puntoExpedicionId` (ausentes: SIFEN usa los del emisor por defecto); timbrado, numeración y datos del emisor los pone SIFEN desde la API key (nunca se envía `emisor`). Notas de crédito/débito exigen `referenceCdc` e `issueReasonCode` (1-8).
- **Venta a crédito**: `credit` es obligatorio en `INVOICE` con `paymentCondition: CREDIT` y prohibido en el resto (422 `invalid-invoice` sin persistir). Lleva `termDays` (1-999 → `credito.condicion=1`, `plazo: "N dias"`) **o** `installments: [{amount, dueDate?}]` (→ `condicion=2`, cuotas en la moneda del header, `dueDate` `yyyy-MM-dd`), más `downPayment` opcional (→ `montoEntrega`). Que las cuotas cubran `total − downPayment` lo valida SIFEN (400 → `FAILED`). Ej.: `"credit": {"installments": [{"amount": 3000, "dueDate": "2026-11-10"}], "downPayment": 1400}`.
- **Reintento tras `FAILED`**: la idempotencia evita duplicar efectos, no reintentar un fallo sin efecto. Repetir la misma emisión o cancelación (mismo `sourceRef` y payload) cuya operación quedó `FAILED` la devuelve a la cola en el paso fallido (mismo `operationId`, 202); como conserva `lastError`, la saga verifica en SIFEN antes de reenviar y adopta el documento/cancelación si ya existe. No se reintenta un `RECHAZADO` (SIFEN procesó y rechazó el DE: repetir no lo cambia; corregir requiere otro `sourceRef`), ni `DEAD` (replay de consola), ni operaciones en curso o exitosas (se devuelven tal cual). Otro payload sigue siendo 409 `idempotency-conflict`. Se descartó una clave derivada `{sourceRef}#n`: el `sourceRef` es también el `transactionId` de SIFEN y la clave de las consultas y del webhook.
- **Saga `ISSUE_INVOICE`** (`[NEXUS-FISCAL]`): `SUBMIT` → (`TRACK` si SIFEN quedó en `PENDIENTE_ENVIO`/`EN_PROCESO`: consultas a 1 m, 5 m, 15 m, 1 h y cada 6 h, máximo 72 h). Todo reenvío va precedido de una verificación `GET /facturas/{transactionId}`: si existe se adopta, solo un 404 reenvía y siempre con el mismo `transactionId`. Un DE emitido no se compensa: se anula con `CANCEL_INVOICE` (paso `SUBMIT_CANCEL`, mismo criterio de verificación).
- **Errores SIFEN**: timeout o 5xx tras un `POST` → `UNCERTAIN`; 409 `EMISION_EN_CURSO` → espera `Retry-After` sin gastar intento; 409 `IDEMPOTENCY_CONFLICT` → `DEAD`; 401/403 → `DEAD credentials`; 400/402/404/409 `BUSINESS_RULE`/422 → `FAILED` con el mensaje de SIFEN; 429 → `Retry-After`; 200 `RECHAZADO` → `FAILED` y la factura queda `REJECTED` con `providerCode`/`providerMessage`.
- **Webhook** `POST /api/v1/webhooks/sifen/{connectionId}` (registrar en SIFEN la URL pública): firma `X-Webhook-Signature` ±300 s, dedupe por `eventId` (`inbound_events`), cuerpo ≤ 256 KB. El evento solo adelanta la consulta a SIFEN de la operación en espera; el estado siempre se lee de SIFEN.
- **Métrica**: `nexus_connector_latency_seconds{connector="SIFEN",operation,outcome}`.

Errores en `application/problem+json` con `type` `https://nexus.mindtechpy.net/problems/<code>`, `code` y `correlationId` (`X-Correlation-Id`).

### Pedidos y catálogo GiftHub ↔ POS (S5)

- **Conexiones**: `POS` con credencial `apiKey` (`X-Api-Key` del POS, scopes `catalog:read customers:write orders:write`) y settings opcionales `defaultCustomerId` (cliente cuando el pedido no trae teléfono E.164), `branchId`, `warehouseId` (si faltan, el POS usa los de la key). `GIFTHUB` con `apiKey` (`gh_live_…`) y `webhookSecret`; setting `dryRun: true` para probar el catálogo sin escribir.
- **Integraciones**: `ORDER` con origen GiftHub y destino POS; `CATALOG` con origen POS y destino GiftHub (`scheduleEverySeconds`, default 300).
- **`[NEXUS-ORDER]` CREATE_ORDER** (webhook `order.created`, clave = id del pedido): `RESOLVE_CUSTOMER` → `CREATE_ORDER` (`Idempotency-Key: GIFTHUB:{orderId}`; tras un timeout consulta `GET /api/integration/orders/GIFTHUB/{ref}` antes de reenviar) → `NOTIFY_GIFTHUB` (`CONFIRMED` con `external_ref`/`external_number` de la venta). POS 422 `insufficient-stock|unknown-product` → `CANCELLED` en GiftHub con el motivo y `FAILED`; si el pedido ya está pagado en GiftHub no se cancela: `DEAD manual-reconciliation`. NOTIFY agotado → `DEAD` y el replay solo reejecuta NOTIFY.
- **ORDER_STATUS** (paso único `SYNC`, lee el pedido en GiftHub antes de cada acción): el POS avisa `POST /api/v1/orders/GIFTHUB/{externalRef}/status` (scope `orders:write`, lo envía su `ChannelOrderStatusProcessor` al cobrar o anular) → `COMPLETED`: pagado y luego cada transición válida hasta `DELIVERED` (`PREPARING → READY → [OUT_FOR_DELIVERY →] DELIVERED`); `CANCELLED`: `CANCELLED` en GiftHub. `order.cancelled` de GiftHub con venta creada → anula la venta pendiente en el POS. Si los sistemas no coinciden (pagado, entregado o ya cobrado) → `DEAD manual-reconciliation`.
- **`[NEXUS-CATALOG]` CATALOG_SYNC**: por página de 500 del POS, un `PUT /products/bulk`; un fallo reintenta esa página. Programado por ventana (`{integrationId}:{yyyyMMddHHmm}`) y manual con `POST /api/admin/integrations/{id}/run` (operator+, auditado). Al terminar la corrida completa, los productos mapeados que el POS ya no lista se sacan de la venta en GiftHub (`archived: true` en el bulk: quedan con `available = false`) y se olvida su mapeo; si el POS vuelve a listarlos, la próxima corrida los repone. Un catálogo vacío del POS no archiva nada.
- **Webhook** `POST /api/v1/webhooks/gifthub/{connectionId}` (público, sin token): HMAC `v1=` con cualquiera de las firmas de rotación, ±300 s, dedupe por `X-GiftHub-Delivery` en `inbound_events`, cuerpo ≤ 256 KB (413), rate limit por conexión (429). Si la integración no acepta trabajo responde 429 para que GiftHub reintente.
- **Alta (una vez)**: en GiftHub un super admin habilita `integrationsEnabled` del negocio; la dueña crea la integración, copia API key y secreto, y carga el webhook `https://nexus.mindtechpy.net/api/v1/webhooks/gifthub/{connectionId}` con eventos `order.created` y `order.cancelled` e "Incluir datos del cliente". En el POS se crea la API key de integración con los tres scopes, y el cliente m2m de Nexus del tenant necesita `orders:write`.

## E2E local (POS + SIFEN + GiftHub reales)

`scripts/e2e-local.sh [setup] [fiscal] [discounts] [nc_rejected] [catalog] [order] [delivery] [coupon] [ops] [revive]` (sin argumentos, todas) recorre los escenarios contra los stacks locales y sale ≠ 0 en el primero que falla: emisión, NC y anulación; descuentos de línea (incluida qty 3 − 1000, que Nexus parte en dos ítems) y general de la venta (total del DE de SIFEN = total de la venta); NC rechazada por SIFEN (la factura sigue ISSUED); catálogo; pedidos; delivery con topping (total POS = total GiftHub) y topping sin código (GiftHub CANCELLED con motivo); delivery con cupón (total POS = total GiftHub = total SIFEN); pausa/DEAD/replay; y el webhook de SIFEN que revive una emisión DEAD. Tarda ~10-20 min: SIFEN en modo mock a veces deja el DE en `PENDIENTE_ENVIO` y lo reenvía cada 5 min.

| Stack | Cómo levantarlo | URL |
|---|---|---|
| Nexus | `docker compose --env-file .env up -d --build` (postgres, `nexus-api`, `nexus-console`) | consola http://localhost:4200, API :8090 / :8091 |
| POS (`feat/nexus-integration`) | `docker compose -p punto-venta -f ../punto-venta-nexus/docker-compose.yml -f local-stack/punto-venta.override.yml --project-directory ../punto-venta-nexus up -d --build` | http://localhost:5175, API :3001 |
| GiftHub (`feat/nexus-external-order`) | `docker compose -p gift-hub up -d --build` en su worktree, con `SETTINGS_ENCRYPTION_KEY` y `WEBHOOKS_ALLOW_LOCAL_HTTP=true` en su `.env` | http://localhost:5173, API :3000 |
| SIFEN (mock) | stack de `electronico-sifen` con `APP_WEBHOOKS_SSRF_GUARD_ENABLED=false` en su override local | http://localhost:5174, API :8083 |

- Credenciales en `.env.local-stack` (gitignored): `NEXUS_E2E_ADMIN_*`, `POS_TENANT_ADMIN_*`, `SIFEN_API_*`, `GIFTHUB_OWNER_*`. Lo que el script crea (cliente m2m, API key del POS, integración de GiftHub) se agrega ahí la primera vez.
- Los contenedores se ven por `host.docker.internal` (allowlist del perfil `local`). GiftHub solo llama a `http://` y redes privadas con `WEBHOOKS_ALLOW_LOCAL_HTTP=true`, que no arranca con `NODE_ENV=production`.
- Una vez, como SUPER_ADMIN de SIFEN: webhook del emisor a `http://host.docker.internal:8090/api/v1/webhooks/sifen/{connectionId}` con el secreto `SIFEN_E2E_WEBHOOK_SECRET`. Sin él la emisión igual termina por consulta (1 m, 5 m).
- El script ajusta datos de prueba del tenant del POS (punto de expedición mapeado a SIFEN, modo `ELECTRONIC`) y apaga mientras dura cada caso `posDeferPayment` (venta a crédito) y `allowNegativeStock` (pedido sin stock), restaurándolos.

## Despliegue (cluster mindtechpy, GitOps)

- `k8s/` lo sincroniza Argo CD (Application `nexus` en `k8s-platforms/argocd/apps/nexus.yaml`), namespace `nexus`. Única entrada pública: `https://nexus.mindtechpy.net` → `nexus-console`; la consola hace proxy de `/api/admin/` y `/api/v1/webhooks/` a `nexus-api:8080`.
- `.github/workflows/deploy.yml`: en PR corre tests y build sin push; en `main` publica `ghcr.io/poravv/mindtech-nexus-{api,console}:sha-<7>`, crea el Secret `nexus-secrets` y commitea el tag en `k8s/*-deployment.yaml` (`chore(deploy): nexus sha-<7> [skip ci]`). Rollback = revertir ese commit.
- GitHub Secrets del repo: `NEXUS_DB_NAME`, `NEXUS_DB_USER`, `NEXUS_DB_PASSWORD`, `NEXUS_JWT_SIGNING_KEY`, `NEXUS_MASTER_KEY`, `NEXUS_BOOTSTRAP_ADMIN_EMAIL`, `NEXUS_BOOTSTRAP_ADMIN_PASSWORD`. El job `release` corre en el runner self-hosted del cluster (kubectl ya configurado): el repo necesita ese runner registrado.
- **Primer despliegue**: el tag inicial de `k8s/*-deployment.yaml` no corresponde a una imagen publicada. El primer run de `deploy.yml` en `main` construye ambas imágenes y pinea el tag real; recién después se mergea la Application en `k8s-platforms` para que Argo sincronice. Luego hacer públicos los paquetes GHCR (`k8s-platforms/docs/ghcr-public-packages.md`).
- Backups: CronJob `postgres-backup` (02:30) con dump local en `backup-pvc` y copia cifrada a R2 bajo `nexus/`; el Secret `backup-r2` lo crea `k8s-platforms` (`BACKUP_NAMESPACES`). Probar con `gh workflow run ops-runbook.yml -R poravv/k8s-platforms -f action=run-backup -f namespace=nexus -f target=postgres-backup` y revisar el tamaño del dump.
