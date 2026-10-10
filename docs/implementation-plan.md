# Plan de implementación — MindTech Nexus MVP Lean

Cada slice es ejecutable por un agente de programación independiente. Antes de empezar, el agente lee `MINDTECH_NEXUS_ESPECIFICACION_TECNICA.md` (v1.1), `docs/discovery.md` y los ADR. Convenciones obligatorias para todos: hexagonal (domain sin frameworks), simple/específico/sin especulación/sin código muerto, documentar APIs públicas, tests en bordes, commits atómicos `type: descripción`, nunca secretos en Git, logs con prefijo de flujo. Español en docs/UI/logs, inglés en código.

## Orden y paralelismo

```
S1 ──► S2 ──► S3 (SIFEN + saga fiscal) ──┐
              └► S5 (POS/GiftHub conectores + sagas) ──► S7 (E2E + CI + K8s)
S4 (punto-venta) ──── en paralelo desde el inicio (solo necesita los contratos de la spec §8 y §11) ─┘
S6 (consola Angular) ── en paralelo desde S1 terminado (usa la admin API de S1; se completa con S2 para operaciones)
```
- **Paralelo inmediato**: S1 y S4 (repos distintos). Tras S1: S2 y S6. Tras S2: S3 y S5 en paralelo. S7 cierra.
- Integración entre repos: S4 y S5 se verifican juntos en S7 con compose.

---

## S1 — Esqueleto backend, Flyway, tenants, auth m2m y consola, admin API base

**Repo:** `MindTech-Nexus` · **Paths:** `nexus-api/**`, `compose.yaml`, `README.md`

**Objetivo:** proyecto Spring Boot 4.1.1 / Java 21 arrancable con Postgres, migraciones, auth propia, tenants, clientes m2m, usuarios, conexiones cifradas y auditoría; ArchUnit vigente.

**Crear:**
- `nexus-api/pom.xml`: parent `spring-boot-starter-parent:4.1.1`, `java.version=21`; deps `spring-boot-starter-webmvc`, `starter-security`, `starter-security-oauth2-resource-server`, `starter-data-jdbc` (solo `JdbcClient`/`DataSource`), `starter-validation`, `starter-actuator`, `flyway-core` + `flyway-database-postgresql`, `postgresql`, `micrometer-registry-prometheus`, `springdoc-openapi-starter-webmvc-ui:3.1.1`, `logstash-logback-encoder`; test: `starter-test`, `testcontainers:postgresql:1.21.4`, `archunit-junit5:1.3.0`, `wiremock-standalone:3.10.0`.
- `src/main/java/com/mindtechpy/nexus/domain/`: `tenant/{Tenant, TenantStatus}`, `identity/{User, Role, ApiClient, Scope, PasswordHash}`, `connection/{Connection, ConnectorType, ConnectionCredentials, BaseUrlPolicy}`, `audit/AuditEvent`, `shared/{TenantId, Ids, DomainException}`. Sin anotaciones.
- `application/port/out/`: `TenantRepository`, `UserRepository`, `ApiClientRepository`, `ConnectionRepository`, `AuditRepository`, `SecretCipherPort`, `IdentityProviderPort`, `PasswordHasherPort`, `ClockPort`.
- `application/usecase/`: `CreateTenant`, `CreateUser`, `LoginUser`, `RefreshSession`, `CreateApiClient` (devuelve el secreto una vez), `RotateApiClientSecret`, `IssueClientToken`, `UpsertConnection`, `TestConnection` (usa `ConnectorHealthPort` con impl stub en S1), `RecordAudit`.
- `infrastructure/persistence/` (`JdbcClient`): un repositorio por puerto; `db/migration/V001__core.sql` con `tenants, users, user_sessions, api_clients, connections, audit_events` según spec §6 (las tablas de operaciones van en S2).
- `infrastructure/security/`: `SecurityConfig` (stateless; `/oauth/token` Basic; `/api/v1/**` resource server JWT `typ=client`; `/api/admin/**` JWT `typ=user` + roles; `/actuator/health/**` y `/actuator/prometheus` abiertos solo en puerto management 8081), `HmacJwtSupport` (`JwtEncoder/Decoder` HS256 con `NEXUS_JWT_SIGNING_KEY`), `TenantContext` (extrae `tenant_id`), `BcryptPasswordHasher`, `AesGcmSecretCipher`, `LoginRateLimitFilter` (10/min/IP, memoria).
- `infrastructure/web/`: `OAuthTokenController` (`POST /oauth/token`), `AdminAuthController` (`/api/admin/auth/{login,refresh,logout,me}`), `AdminTenantController`, `AdminUserController`, `AdminApiClientController`, `AdminConnectionController` (`POST/GET/PUT`, `POST /{id}/test`), `AdminAuditController`; `ProblemDetailsHandler` (RFC 9457, `code`, `correlationId`); `CorrelationIdFilter`.
- `infrastructure/config/`: `NexusProperties` (`nexus.security.jwt-secret`, `nexus.security.master-key`, `nexus.connectors.allowed-hosts`, `nexus.bootstrap.admin-email/password`, `nexus.worker.enabled`), `BootstrapAdminRunner`, logback JSON con masking.
- `src/main/resources/application.yml` (+ `application-local.yml`): puerto 8080, management 8081, Flyway, actuator `health.probes.enabled`, `logging.structured`.
- `compose.yaml`: `postgres:16-alpine` + `nexus-api` (perfil `local`).
- `src/test/java/.../ArchitectureTest.java` (ArchUnit) y base `AbstractPostgresIT` (Testcontainers).
- `README.md`: cómo correr, variables, cómo crear el primer tenant con `curl`.

**Contratos a respetar:** spec §5 (claims, roles, cookies), §6 (tablas), §8 convenciones (problem+json). Secretos nunca en respuestas; `credentials_enc` write-only.

**Tests:** `ArchitectureTest`; `AesGcmSecretCipherTest` (round-trip, AAD distinto falla); `OAuthTokenIT` (client válido → JWT con `tenant_id` y scopes; secreto rotado válido 24 h; revocado 401); `AdminAuthIT` (login, refresh por cookie, logout revoca, bloqueo tras 5 fallos, cambio obligatorio de password al bootstrap); `TenantIsolationIT` (usuario tenant A obtiene 404 sobre conexión de B; cliente de A no puede usar `?tenantId=B`); `ConnectionIT` (base_url fuera de allowlist → 422; test de conexión no devuelve credenciales; auditoría registrada).

**Aceptación:**
```bash
cd nexus-api && ./mvnw -B verify                      # verde, incluye ArchUnit y Testcontainers
docker compose up -d && curl -s localhost:8081/actuator/health/readiness   # {"status":"UP"}
curl -u <client_id>:<secret> -d grant_type=client_credentials localhost:8080/oauth/token   # access_token
curl -s localhost:8080/api/admin/tenants -H "Authorization: Bearer <user-jwt>"           # lista
```

---

## S2 — Motor de operaciones durable

**Repo:** `MindTech-Nexus` · **Paths:** `nexus-api/src/main/java/com/mindtechpy/nexus/{domain/operation,domain/integration,application/saga,application/port,infrastructure/jobs,infrastructure/persistence}`, `V002__operations.sql`

**Objetivo:** cola en Postgres con `SKIP LOCKED`, idempotencia, retries/backoff, DLQ con replay, estados de integración, rate limit y concurrencia; API de operaciones; framework de sagas por pasos.

**Crear:**
- `domain/operation/`: `Operation` (estados `PENDING, RUNNING, RETRY, SUCCEEDED, FAILED, DEAD`), `OperationKind`, `OperationStep`, `RequestHash` (SHA-256 canónico de JSON ordenado), `RetryPolicy` (backoff + jitter), `ConnectorError{kind}` y `ErrorKind` (spec §7.4).
- `domain/integration/`: `Integration`, `IntegrationState` (`ENABLED, PAUSED, DRAINING, DISABLED, EMERGENCY_STOP`) con transiciones permitidas y quién puede (roles), `IntegrationPolicy` (tipado desde jsonb), `Flow`.
- `application/saga/`: `Saga<P>` (interfaz: `List<Step>`, `compensate(op, cause)`), `SagaRunner` (ejecuta el siguiente paso de una `Operation`, aplica gates: estado de integración, `RateLimiterPort`, `maxInFlight`, credenciales; persiste `operation_steps`; clasifica errores; programa `RETRY`; llama a **una sola** `compensate`), `StepResult` (`Succeeded(externalRef)`, `Retry(after)`, `Uncertain`, `Rejected(detail)`, `Dead(reason)`).
- `application/port/out/`: `OperationRepository` (`startOrFind(tenant, kind, key, hash, payload)`, `claim(worker, n)`, `release`, `markStep`, `reschedule`, `rescueExpiredLeases`), `IntegrationRepository`, `RateLimiterPort`, `JobQueuePort` (fachada `enqueue/claim` que en v1 delega en `OperationRepository`).
- `application/usecase/`: `StartOperation`, `GetOperation`, `ListOperations`, `ReplayOperation` (solo `DEAD`, audit), `ChangeIntegrationState` (audit con motivo), `UpsertIntegration`.
- `infrastructure/jobs/`: `OperationWorker` (`@Scheduled(fixedDelay=500)` si `nexus.worker.enabled`, batch 10, `locked_by = hostname:pid`), `LeaseRescueJob` (1 min), `PayloadRetentionJob` (diario, 30 días), `CatalogScheduler` queda para S5.
- `infrastructure/persistence/`: `JdbcOperationRepository` con el claim de la spec §7.2, `JdbcIntegrationRepository`; `V002__operations.sql` (`integrations, operations, operation_steps, entity_mappings, inbound_events`).
- `infrastructure/ratelimit/InMemoryRateLimiter` (token bucket + semáforo por integración; comentario con el techo de 1 réplica).
- Web: `RuntimeOperationController` (`GET /api/v1/operations/{id}`), `AdminOperationController` (`GET`, `GET /{id}`, `POST /{id}/replay`), `AdminIntegrationController` (`POST/GET/PUT`, `POST /{id}/state {state, reason}`).
- Métricas Micrometer de la spec §13 (sin `tenant_id`).

**Contratos:** spec §7 completa; `Saga` es lo que implementan S3 y S5; una saga de prueba `EchoSaga` (WireMock) sirve para los tests de este slice y se borra en S3 si ya no se usa.

**Tests (Testcontainers):** `ClaimConcurrencyIT` (2 workers, 100 ops, ninguna procesada dos veces); `LeaseRescueIT`; `IdempotencyIT` (mismo hash → misma op; distinto → 409); `RetryBackoffIT` (TRANSIENT reintenta con delay creciente y jitter; `REJECTED` → FAILED sin retry; `AUTH` → DEAD; agotado → DEAD); `ReplayIT` (DEAD → PENDING, misma clave, steps SUCCEEDED no se reejecutan); `IntegrationStateIT` (PAUSED retiene sin contar intentos; DRAINING rechaza nuevas con 409 y termina las existentes; EMERGENCY_STOP solo `platform_admin`); `RateLimitIT` (burst 5, luego RETRY +1 s); `RequestHashTest` (orden de claves y nulos no cambian el hash).

**Aceptación:** `./mvnw -B verify` verde; `curl -X POST /api/admin/integrations/{id}/state -d '{"state":"PAUSED","reason":"…"}'` detiene el despacho (métrica `nexus_queue_depth{status="PENDING"}` sube) y `ENABLED` lo reanuda sin replay.

---

## S3 — Conector SIFEN y saga fiscal

**Repo:** `MindTech-Nexus` · **Paths:** `domain/fiscal`, `application/saga/fiscal`, `application/port/out/SifenPort.java`, `infrastructure/connectors/sifen`, `infrastructure/web/runtime/InvoiceController.java`, `infrastructure/web/webhooks/SifenWebhookController.java`

**Objetivo:** `POST/GET /api/v1/invoices`, cancelación, KuDE, saga `ISSUE_INVOICE` y `CANCEL_INVOICE`, webhook SIFEN.

**Crear:**
- `domain/fiscal/`: `InvoiceRequest` (contrato canónico spec §8.1, validaciones: `sourceRef` regex y ≤100, items no vacíos, `vatRate` ∈ {0,5,10}, `referenceCdc` obligatorio en notas), `InvoiceStatus` (canónico) y `ProviderStatus` (SIFEN) con el mapeo de la spec, `InvoiceResult`.
- `application/port/out/SifenPort`: `submit(credentials, transactionId, request) → SubmitOutcome`, `getByTransaction(...)`, `cancel(...)`, `kude(cdc, template)`.
- `application/saga/fiscal/IssueInvoiceSaga` (pasos `SUBMIT`, `VERIFY`, `TRACK` con gates de orden; `compensate` única: marca, step, audit, métrica; log `[NEXUS-FISCAL]`), `CancelInvoiceSaga` (`SUBMIT_CANCEL`, `VERIFY`), `IssueInvoice`/`GetInvoice`/`CancelInvoice`/`GetKude` casos de uso.
- `infrastructure/connectors/sifen/`: `SifenRestClient` (RestClient, connect 5 s, read `policy.readTimeoutMs`, sin redirects), `SifenHmacSigner` (canónico `METHOD\nPATH?QUERY\nTS\nNONCE\nhex(body)`, headers `X-Timestamp`, `X-Nonce`, `X-Signature`, más `X-Access-Key`/`X-Secret-Key`), `SifenMapper` (canónico → `FacturarTransaccionRequest`; nunca envía `emisor`), `SifenErrorClassifier` (tabla spec §7.4: `EMISION_EN_CURSO` → IN_PROGRESS, `IDEMPOTENCY_CONFLICT` → CONFLICT, `VALIDATION_ERROR/BUSINESS_RULE` → REJECTED, 401/403 → AUTH, 429 → RATE_LIMITED con `Retry-After`, timeout de lectura → UNCERTAIN).
- `SifenWebhookController` (`POST /api/v1/webhooks/sifen/{connectionId}`): verifica `X-Webhook-Signature: sha256=HMAC(secret, ts + "." + body)` con el secreto de la conexión (`settings.webhookSecret` cifrado), dedupe por `eventId` en `inbound_events`, actualiza la operación `ISSUE_INVOICE` por `transactionId` (adopta estado, cierra `TRACK`).
- Fixtures: copiar y anonimizar `electronico-sifen-backend/examples/*.json` a `src/test/resources/sifen/`.

**Contratos:** spec §8.1 y §9; contrato SIFEN en `docs/discovery.md` §3.3-3.6.

**Tests:** `SifenHmacSignerTest` con los vectores de `electronico-sifen-backend/docs/INTEGRACION_API.md:127-139`; `SifenMapperTest`; `IssueInvoiceSagaIT` (WireMock) por ramas: 200 APROBADO → SUCCEEDED; 200 PENDIENTE_ENVIO → TRACK → poll APROBADO; read timeout → VERIFY 200 adopta; timeout → VERIFY 404 → re-SUBMIT con el mismo `transactionId`; 409 EMISION_EN_CURSO → RETRY 30 s sin contar intento; 409 IDEMPOTENCY_CONFLICT → DEAD; 401 → DEAD(credentials); 400 VALIDATION_ERROR → FAILED con detalle; 429 respeta `Retry-After`; webhook con firma válida cierra TRACK y con firma inválida 401; evento duplicado 200 sin cambios. `InvoiceControllerIT`: 202 persistido antes de responder; mismo `sourceRef` mismo payload → 200 con estado; distinto payload → 409; cliente de otro tenant → 404; KuDE pasa `application/pdf`.

**Aceptación:** `./mvnw -B verify`; con WireMock local (`compose --profile mocks`), `POST /api/v1/invoices` con `examples/factura-consumidor-final-innominado.json` adaptado devuelve 202 y en <2 s `GET /api/v1/invoices/{sourceRef}` → `APPROVED` con `cdc`.

---

## S4 — punto-venta: cliente a Nexus, retiro del adaptador viejo, endpoints de integración

**Repo:** `punto-venta` (`apps/backend`) · corre en paralelo con S1-S3.

**Objetivo:** el POS factura vía Nexus y expone auth m2m + catálogo + clientes + pedidos para Nexus. Leer `CLAUDE.md`/`AGENTS.md` del POS (nunca `pnpm lint`; migraciones aditivas; `organizationId` en todo `where`).

**A. Retiro y reemplazo fiscal (un PR):**
- Eliminar: `src/modules/invoicing/application/services/electronic-invoice-api.service.ts`, `electronic-invoice-sync.service.ts`, `sifen-hmac.util.ts`, `src/modules/invoicing/.../sifen.controller.ts` (y su registro en `invoicing.module.ts`), specs asociados, `docs/integracion-factura-electronica/swagger.yml`, env `ELECTRONIC_INVOICE_FALLBACK_TO_LEGACY`, `ELECTRONIC_INVOICE_SYNC_*`, `ELECTRONIC_INVOICE_REQUIRE_BRANCH_MAPPING` en `config/app.config.ts:111-146` y `k8s/backend-configmap.yaml`.
- Crear `src/modules/nexus/`: `nexus.module.ts`; `infrastructure/nexus-http.client.ts` (axios con `NEXUS_BASE_URL`, token client_credentials cacheado hasta exp−60 s, `X-Correlation-Id`); `application/nexus-fiscal.adapter.ts` implementa `FiscalIntegrationPort` (`submitInvoice(sale) → 202/200`, `getInvoiceStatus(sourceRef)`, `cancelInvoice(sourceRef, reason)`, `getKude(sourceRef, template)`); `application/nexus-fiscal-sync.job.ts` (BullMQ **repeatable** cada 60 s, una sola instancia, lista facturas con `external_estado` no final por organización y consulta `GET /api/v1/invoices/{sourceRef}`).
- `invoice-electronic.service.ts`: `transactionId = sale.id` (uuid) y construir el contrato canónico de Nexus (spec §8.1) en lugar de `FacturarTransaccionRequest`; mantener `applyExternalStatusSnapshot` mapeando `status` canónico → `external_estado`.
- Migración `…-FiscalCredentialsToNexus.ts`: en `fiscal_credentials` renombrar `access_key_enc → client_id`, `secret_key_enc → client_secret_enc`, eliminar `emisor_id`, `environment`; `base_url` queda nullable (override local). Entidad, service, controller y `POST /fiscal-credentials/test` (llama `/oauth/token`) actualizados.
- Migración que **elimina** `idx_invoices_external_transaction_id_unique` y crea `UNIQUE(organization_id, external_transaction_id)`.

**B. Auth m2m e integración (otro PR):**
- Migración `integration_api_keys(id, organization_id, name, key_prefix, key_hash, scopes text[], system_user_id, default_branch_id, default_warehouse_id, status, last_used_at, created_by_user_id)`; `sales.channel varchar NULL`, `sales.external_ref varchar NULL`, `UNIQUE(organization_id, channel, external_ref)` parcial; `customers.phone_e164 varchar NULL` con `UNIQUE(organization_id, phone_e164)` parcial; backfill de `phone_e164` desde `mobile/phone` normalizado si es posible, sin fallar.
- `src/modules/integration/`: `integration-api-key.guard.ts` (header `X-Api-Key: pv_live_<prefix>_<secret>`, SHA-256, marca `request.user` con `organizationId`, `userId = system_user_id`, `integrationScopes`; registrado con decorador `@IntegrationKey()` que hace bypass de `JwtAuthGuard`/`EmailVerifiedGuard`/`StepUpGuard`), `integration-api-keys.controller.ts` (`POST/GET/DELETE /integration-api-keys`, admin + step-up, secreto una vez), `integration-catalog.controller.ts` (`GET /api/integration/catalog?warehouseId&page&limit≤500` → `active && sellable`, `code, name, price, stockQty, tracked, category, imageUrl, updatedAt`), `integration-customers.controller.ts` (`POST /api/integration/customers/resolve {phoneE164, name, document?}` → upsert por `phone_e164`), `integration-orders.controller.ts` (`POST /api/integration/orders` con header `Idempotency-Key` y body `{channel, externalRef, customerId, branchId?, warehouseId?, items[{code, quantity}], delivery?, paymentMethod?, notes?}` → `201 {saleId, saleNumber, total, status}`; `GET /api/integration/orders/:channel/:externalRef`; `POST …/cancel {reason}`).
- `application/create-external-order.use-case.ts`: reutiliza `SaleService.createWithPayments` con opciones nuevas `{channel, externalRef, skipCreditCheck: true, requireCashSession: false}`, valida productos (`active && sellable`), stock en depósito (`422 insufficient-stock`), precios del POS, `source='ONLINE'`, `status=PENDING`, `payments=[]`. `clientSaleId = uuidv5(channel:externalRef)` para reutilizar la idempotencia existente.
- `nexus-order-status.listener.ts`: en `sale.completed`/`sale.cancelled` con `channel` no nulo → BullMQ `nexus-order-status` → `POST {NEXUS}/api/v1/orders/{channel}/{externalRef}/status`.
- `k8s/network-policy.yaml`: agregar ingress desde `namespaceSelector: kubernetes.io/metadata.name: nexus` al puerto 3000. `k8s/backend-configmap.yaml`: `NEXUS_BASE_URL=http://nexus-api.nexus.svc.cluster.local:8080`.
- Docs: reemplazar `docs/integracion-factura-electronica/SIFEN_INTEGRACION.md` por `docs/integracion-nexus.md` (fiscal + integración de canal).

**Tests (jest, existentes como patrón):** `nexus-fiscal.adapter.spec.ts` (token cacheado, 202/200, mapeo de estados, 409 → no reintenta); `nexus-fiscal-sync.job.spec.ts`; `integration-api-key.guard.spec.ts` (key inválida 401, scope insuficiente 403, org inactiva 401); `create-external-order.use-case.spec.ts` (stock insuficiente 422, producto no vendible 422, idempotencia por `Idempotency-Key`: mismo → 200 misma venta, distinto payload → 409, sin caja ni crédito); e2e `integration-orders.e2e-spec.ts`; e2e existente `invoice-from-sale-auto-generate.e2e-spec.ts` adaptado a Nexus mockeado (nock/msw).

**Aceptación:**
```bash
pnpm --filter @punto-venta/backend test && pnpm --filter @punto-venta/backend test:e2e
grep -rn "X-Secret-Key\|sifen-hmac\|electronic-invoice-api" apps/backend/src | wc -l   # 0
pnpm --filter @punto-venta/backend typeorm schema:log   # sin drift
```

---

## S5 — Conectores POS y GiftHub, sagas de pedido y catálogo, cambio en gift-hub

**Repos:** `MindTech-Nexus` y `gift-hub` · depende de S2 (y de los contratos de S4, no de su merge).

**Objetivo:** webhook GiftHub → saga `CREATE_ORDER` → POS; `ORDER_STATUS` POS → GiftHub; `CATALOG_SYNC` programado.

**Nexus — crear:**
- `domain/order/` (`InboundOrder`, `OrderStatusUpdate`, `OrderChannel`), `domain/catalog/` (`CatalogItem`, `CatalogDiff` no: full sync).
- `application/port/out/PosPort` (`resolveCustomer`, `createOrder(idempotencyKey, …)`, `getOrder(channel, ref)`, `cancelOrder`, `fetchCatalog(page)`), `GiftHubPort` (`setOrderStatus`, `markPaid`, `bulkUpsertProducts(items, dryRun)`), `application/saga/order/CreateOrderSaga` (`RESOLVE_CUSTOMER`, `CREATE_ORDER`, `NOTIFY_GIFTHUB`; `VERIFY` tras UNCERTAIN; `compensate` única: `CANCELLED` en GiftHub con motivo cuando el POS rechazó; log `[NEXUS-ORDER]`), `OrderStatusSaga` (`MARK_PAID`, `SET_STATUS`), `application/saga/catalog/CatalogSyncSaga` (`FETCH_POS`, `PUSH_GIFTHUB`; log `[NEXUS-CATALOG]`), casos de uso `ReceiveGiftHubEvent`, `ReportOrderStatus`, `RunCatalogSync`.
- `infrastructure/connectors/pos/` (`PosRestClient`, header `X-Api-Key`, timeouts 5/30 s, `PosErrorClassifier`: 422 → REJECTED, 409 idempotencia → CONFLICT), `infrastructure/connectors/gifthub/` (`GiftHubRestClient`, Bearer, `Idempotency-Key` obligatorio en `/orders/*`, lotes ≤500 en `bulk`), `GiftHubWebhookVerifier` (`v1=HMAC-SHA256(secret, ts + "." + body)`, ±300 s), `GiftHubWebhookController` (`POST /api/v1/webhooks/gifthub/{connectionId}`: 401 firma, dedupe `X-GiftHub-Delivery`, `ping` → 200, `order.created` → `StartOperation`, `order.cancelled` → cancela en POS si `PENDING`), `RuntimeOrderController` (`POST /api/v1/orders/{channel}/{ref}/status`, `GET …`), `CatalogScheduler` (`@Scheduled` cada 60 s crea `CATALOG_SYNC` para integraciones `CATALOG` cuya última corrida supere `scheduleEverySeconds`), `AdminIntegrationController.run` (`POST /api/admin/integrations/{id}/run`).
- Settings de conexión POS: `branchRef`, `warehouseRef`, `defaultCustomerId`; GiftHub: `dryRun`.

**gift-hub — modificar:** migración Prisma `add_order_external_ref` (`Order.externalOrderId String?`, `externalOrderNumber String?`, índice `(tenantId, externalOrderId)`); `apps/api/src/modules/integrations/infrastructure/integration-orders-controller.ts:44` acepta `external_ref`, `external_number` opcionales en `POST /orders/:id/status`; `orders/application/manage-admin-orders.ts` los persiste; panel muestra el número externo en el detalle; `docs/manual-integracion.md` documenta los campos. Test vitest del endpoint.

**Tests Nexus (WireMock POS + GiftHub):** `GiftHubWebhookVerifierTest` (vector construido con el algoritmo de `webhook-signature.ts:9`); `CreateOrderSagaIT` por ramas: éxito (CONFIRMED con `saleNumber`), POS 422 stock → compensación `CANCELLED` + FAILED, POS timeout → VERIFY 200 adopta, VERIFY 404 → reintenta con mismo `Idempotency-Key`, NOTIFY agotado → DEAD y replay solo reejecuta NOTIFY, sin cliente en evento → `defaultCustomerId`; `OrderStatusSagaIT` (COMPLETED → paid+DELIVERED; CANCELLED → CANCELLED; duplicado idempotente); `CatalogSyncSagaIT` (3 páginas POS → 2 lotes GiftHub; `dryRun`; error en lote 2 → RETRY reanuda desde `PUSH_GIFTHUB`); `WebhookControllerIT` (firma inválida 401; delivery duplicada 200 sin nueva operación; conexión inexistente 404; body > 256 KB 413).

**Aceptación:** `./mvnw -B verify`; en gift-hub `npm test -w apps/api` verde y `prisma migrate deploy` aplica; con mocks, un `POST` simulando `order.created` termina en `GET /api/admin/operations/{id}` → `SUCCEEDED` con steps `RESOLVE_CUSTOMER, CREATE_ORDER, NOTIFY_GIFTHUB`.

---

## S6 — Consola Angular

**Repo:** `MindTech-Nexus` · **Path:** `nexus-console/**` · depende de S1 (y S2 para operaciones/integraciones).

**Objetivo:** consola completa de la spec §12 contra la admin API.

**Crear:** `ng new nexus-console --standalone --style=scss --ssr=false` (Angular 22.2); `src/app/core/` (`auth.service.ts` con signals + `authInterceptor` Bearer/refresh, `role.guard.ts`, `api-client.ts`, `problem-details` → toasts); `src/app/shared/ui/` (atoms: `Button`, `Badge` de estado, `Input`; molecules: `StatCard`, `FilterBar`, `ConfirmDialog` con motivo; organisms: `DataTable` paginado, `JsonViewer` sanitizado); `src/app/features/{login,overview,tenants,users,api-clients,connections,integrations,operations,audit}` (rutas lazy standalone). Design tokens en `src/styles/_tokens.scss` (sin hex inline). `Dockerfile` (build `node:22-alpine` → `nginxinc/nginx-unprivileged:1.28-alpine`, uid 101, puerto 8080) y `nginx.conf` con `location /api/admin/` y `location /api/v1/webhooks/` → `proxy_pass http://nexus-api:8080;` (sin exponer el resto). `proxy.conf.json` para dev contra `localhost:8080`.

**Contratos:** admin API de S1/S2; roles de la spec §5.2 (la UI oculta acciones no permitidas; el backend es quien autoriza).

**Tests:** unit (Vitest/Karma según default de Angular 22) de `auth.service`, `role.guard`, `DataTable`; e2e mínimo con Playwright (`playwright-cli` skill): login → crear conexión → cambiar integración a PAUSED con motivo → replay de una operación DEAD (backend en compose con datos semilla `scripts/seed-dev.sh`).

**Aceptación:** `npm ci && npm test && npm run build` verde; `docker build` ok; en compose, `http://localhost:8082` muestra login y, con el admin bootstrap, todas las pantallas; a11y: navegación por teclado y contraste AA en tokens; responsive 360 px.

---

## S7 — Docker compose E2E, CI GitHub Actions, manifests K8s/Argo, apertura de red

**Repos:** `MindTech-Nexus` (`compose.yaml`, `scripts/`, `.github/workflows/`, `k8s/`), `k8s-platforms` (`argocd/apps/nexus.yaml`, `.github/workflows/platforms-deploy.yml`), `punto-venta/k8s/network-policy.yaml`, `electronico-sifen-backend/k8s/network-policy.yaml` · depende de S3, S5, S6 (y S4 mergeado para el E2E real).

**Crear en Nexus:**
- `compose.yaml` perfiles: `local` (postgres + nexus-api + nexus-console), `e2e` (+ POS: `build: ../punto-venta/apps/backend` con su postgres/redis/minio; GiftHub: `build: ../gift-hub/apps/api` con su postgres; `wiremock` con stubs SIFEN en `e2e/wiremock/sifen/` generados a partir de `electronico-sifen-backend/examples/`). **Ya existe un stack local parcial** preparado en esta sesión: `local-stack/punto-venta.override.yml` (POS en 3001/5175, red externa `electronico-sifen_default`, Mailpit del stack de electronico-sifen) y `.env.local-stack` (credenciales de prueba, gitignored). Reutilizarlo: el perfil `e2e` se conecta a esa red y, cuando el stack de `electronico-sifen/.local-stack` esté levantado, puede apuntar la conexión SIFEN del tenant de prueba al sifen-backend real en lugar de WireMock. Tras S4, ese override debe quitar `ELECTRONIC_INVOICE_SYNC_ENABLED` (variable eliminada) y agregar `NEXUS_BASE_URL=http://host.docker.internal:8080`. Para GiftHub → Nexus, como exige `https`, el E2E inyecta el evento `order.created` firmado con `scripts/e2e-gifthub-event.sh` (mismo algoritmo HMAC) en lugar de esperar el webhook real; el webhook real se valida en el cluster.
- `scripts/e2e.sh`: levanta, espera readiness, crea tenant/usuario/cliente/conexiones/integraciones por admin API, crea en el POS una API key de integración y una venta, verifica `APPROVED` con `cdc`; dispara `order.created`, verifica venta `PENDING` en el POS y `CONFIRMED` en GiftHub (por su API); fuerza `CATALOG_SYNC` y verifica productos en GiftHub. Sale ≠0 si algo falla.
- `.github/workflows/ci.yml` (PR, `ubuntu-latest`): `./mvnw -B verify`, `npm ci && npm test && npm run build`, `docker build` de ambas imágenes.
- `.github/workflows/deploy.yml` (push `main`, `runs-on: self-hosted`, `permissions: contents: write`): copiar el patrón de `electronico-sifen-backend/.github/workflows/deploy.yml:52-209`: buildx a `ghcr.io/poravv/mindtech-nexus/api` y `/console` (`type=sha,format=short`), Secret `nexus-api-secrets` con `--from-file` desde GitHub Secrets (`NEXUS_DB_NAME, NEXUS_DB_USER, NEXUS_DB_PASSWORD, NEXUS_MASTER_KEY, NEXUS_JWT_SIGNING_KEY, NEXUS_BOOTSTRAP_ADMIN_EMAIL, NEXUS_BOOTSTRAP_ADMIN_PASSWORD`), `sed -i "s|:sha-[a-z0-9]*|:sha-${SHORT_SHA}|"` en `k8s/api-deployment.yaml` y `k8s/console-deployment.yaml`, commit `chore(deploy): nexus sha-<7> [skip ci]` (`github-actions[bot]`, reintento fetch+reset), `kubectl rollout status` hasta 10 min, smoke `wget` a `http://nexus-api:8081/actuator/health` desde el pod de console. Hacer públicos los paquetes GHCR (`k8s-platforms/docs/ghcr-public-packages.md`).
- `k8s/`: `namespace.yaml`; `postgres-{deployment,service,pvc}.yaml` (`postgres:16.10-alpine` o el minor vigente, PVC 5Gi longhorn, `PGDATA` subdir, `Recreate`); `api-deployment.yaml` (copiar `electronico-sifen-backend/k8s/backend-deployment.yaml`: uid 10001, `readOnlyRootFilesystem`, `/tmp` emptyDir, requests 300m/512Mi, limits 1000m/1Gi, probes actuator en 8081, `envFrom` ConfigMap `nexus-api-config`, Secret `nexus-api-secrets`, `replicas: 1`, imagen con tag real desde el primer commit), `api-service.yaml` (8080/8081), `api-configmap.yaml` (`SPRING_DATASOURCE_URL=jdbc:postgresql://postgres.nexus.svc.cluster.local:5432/nexus`, `NEXUS_CONNECTORS_ALLOWED_HOSTS=*.svc.cluster.local`, `NEXUS_WORKER_ENABLED=true`), `console-{deployment,service,configmap}.yaml` (copiar `electronico-sifen-dashboard/k8s/frontend-*.yaml`), `httproute.yaml` (`nexus.mindtechpy.com` → `nexus-console:8080`, `parentRefs public-gateway/traefik`), `network-policy.yaml` (default-deny; ingress: `traefik` → console 8080, `punto-venta` → api 8080, `comercio-central` → api 8080 (por si GiftHub algún día llama interno), mismo ns; egress: kube-dns 53, `postgres` 5432, `punto-venta` 3000, `sifen` 8081, `comercio-central` 3000, 443 solo pod de backup), `servicemonitor.yaml` (puerto `management`, `/actuator/prometheus`, 30 s), `prometheusrule.yaml` (alertas spec §13), `backup-cronjob.yaml` (copiar `punto-venta/k8s/backup-cronjobs.yaml` postgres-backup, prefijo `nexus`).
- `README.md` sección "Despliegue" + runbook corto `docs/runbook.md` (replay DLQ, pausar integración, rotar credenciales, restaurar DB con `ops-runbook.yml`).

**Crear/modificar fuera de Nexus (PRs separados):**
- `k8s-platforms/argocd/apps/nexus.yaml`: copiar `sifen-backend.yaml`; `repoURL: https://github.com/poravv/MindTech-Nexus.git`, `path: k8s`, `destination.namespace: nexus`, `syncPolicy: {automated: {selfHeal: true}, syncOptions: [CreateNamespace=true, ApplyOutOfSyncOnly=true]}` (sin `prune` por el PVC, sin `recurse: false`). `platforms-deploy.yml`: agregar `nexus` a `BACKUP_NAMESPACES` (L784-806). No hace falta Secret `repo-*` (repo público).
- `punto-venta/k8s/network-policy.yaml:12-26` y `electronico-sifen-backend/k8s/network-policy.yaml:41-65`: regla `from: namespaceSelector {kubernetes.io/metadata.name: nexus}` a los puertos 3000 y 8081 respectivamente.
- Manual (documentar en `docs/runbook.md`, no automatizar): CNAME `nexus` → `<tunnel-id>.cfargotunnel.com` proxied en Cloudflare; en SIFEN (SUPER_ADMIN) crear emisor de prueba (`ambiente=2`), API key con scopes `FACTURA, INVOICE_READ, KUDE_READ` y webhook a `https://nexus.mindtechpy.com/api/v1/webhooks/sifen/{connectionId}`; en GiftHub crear integración, API key y webhook con `includeCustomer`.

**Tests/aceptación:**
```bash
docker compose --profile e2e up -d --build && ./scripts/e2e.sh          # exit 0
kubectl -n nexus get pods                                               # api, console, postgres Running (vía MCP k8s-prod solo lectura)
curl -s https://nexus.mindtechpy.com/api/admin/auth/me -I               # 401 (ruta pública viva)
curl -s https://nexus.mindtechpy.com/api/v1/operations/x -I            # 404 de nginx (runtime no expuesto)
argocd app get nexus                                                    # Synced/Healthy (o UI de Argo)
```
Primer hito post-despliegue: emisión real en sifen-test con el tenant piloto y verificación de `APPROVED`/`cdc` antes de habilitar `ambiente=1`.
