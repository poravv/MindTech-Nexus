# Discovery — inventario real de los sistemas que integra MindTech Nexus

Fecha: 10/10/2026. Solo lectura. Sin secretos (cualquier valor sensible aparece como `<redactado>`).
Prefijos: `$POS=/Users/andresvera/Desktop/Proyectos/punto-venta`, `$SIFEN=/Users/andresvera/Desktop/Proyectos/electronico-sifen-backend`, `$GH=/Users/andresvera/Desktop/Proyectos/gift-hub`, `$K8S=/Users/andresvera/Desktop/Proyectos/k8s-platforms`.

## 1. Stack verificado para Nexus

| Componente | Versión fijada | Evidencia |
|---|---|---|
| Java | 21 LTS | Corretto 21.0.8 instalado; sifen-backend usa 21 (`$SIFEN/pom.xml:21`); imágenes `eclipse-temurin:21-jre-alpine` disponibles |
| Spring Boot | 4.1.1 | Maven Central: `spring-boot/4.1.1` publicado 20-ago-2026; `3.5.16` fue el último 3.5 (25-jun-2026). Artefactos 4.1.1 (`starter-webmvc`, `starter-security-oauth2-resource-server`, `starter-data-jdbc`, `starter-validation`, `starter-actuator`) ya resueltos en `~/.m2` |
| springdoc | 3.1.1 (línea compatible con Boot 4) | `~/.m2/repository/org/springdoc/springdoc-openapi-starter-webmvc-ui/3.1.1` |
| Testcontainers | 1.21.4 | Maven Central `<latest>1.21.4` y `~/.m2` |
| WireMock | 3.10.0 (`wiremock-standalone`) | `~/.m2` |
| ArchUnit | 1.3.0 | `~/.m2` |
| Angular | 22.2.2 (`latest`) | `npm view @angular/core dist-tags.latest` |
| Node (console build) | 22 LTS | igual que POS y GiftHub |
| Repo | `github.com/poravv/MindTech-Nexus` público, sin commits | `gh repo view` |

## 2. punto-venta (POS)

### 2.1 Stack y despliegue
- Node 22.16 (`$POS/.nvmrc`), pnpm 10, monorepo `apps/backend` (NestJS ^10.2, TypeORM ^0.3.17, PG, BullMQ/Redis, EventEmitter2, passport-jwt, axios) y `apps/frontend` (React 19, Vite) — `$POS/apps/backend/package.json:28-63`, `$POS/CLAUDE.md:37-41`.
- Local: `docker compose up -d` (postgres 5432, redis 6380, MinIO, backend 3000, frontend 5173) — `$POS/docker-compose.yml`.
- Dockerfile multi-stage `node:22.16-alpine`, healthcheck `/api/health` — `$POS/apps/backend/Dockerfile:2,28,80-84`.
- CI `$POS/.github/workflows/deploy.yml`: tests + drift de migraciones (L149-172), build GHCR `ghcr.io/poravv/punto-venta/{backend,frontend}:sha-<7>`, job `release` en runner self-hosted: crea Secrets por kubectl, corre Job de migraciones, `sed` del tag en `$POS/k8s/backend-deployment.yaml` / `frontend-deployment.yaml`, commit `[skip ci]` (L337-397). Tag actual `sha-18aa6b9`.
- Convenciones (`$POS/AGENTS.md:14-34`, `$POS/CLAUDE.md:1176-1182`): nunca `pnpm lint`; migraciones aditivas; dato fiscal siempre del tenant; todo `where` con `organizationId`; `resolveContext()` para el tenant; stock se decide por movimientos.

### 2.2 Multi-tenant y auth
- `Organization` (`organizations`: `name, ruc, dv`) — `$POS/apps/backend/src/modules/shared/domain/entities/organization.entity.ts:4-18`; settings en `organization_settings`.
- Tenant = claim `organizationId` del JWT (`auth/interfaces/jwt-payload.interface.ts:1-13`; `auth/strategies/jwt.strategy.ts:47-75`). JWT HS256 15 min + refresh cookie (`$POS/CLAUDE.md:300-303`). Guards globales (`app.module.ts:104-125`). Roles `admin|gerencia|ventas|deposito|superadmin`.
- **No hay API keys m2m** (búsqueda `x-api-key|ApiKeyGuard` sin resultados). Rutas `@Public()`: `/api/health`, `/api/files/public/*`, login/activate/refresh (`$POS/CLAUDE.md:1572`).

### 2.3 Integración fiscal actual (a retirar)
- Módulo `$POS/apps/backend/src/modules/invoicing/`: `invoicing.module.ts:61-112` (colas BullMQ `invoice-emission` 3 intentos/backoff 2 s; `create-invoice-from-sale` 3/5 s), `application/services/electronic-invoice-api.service.ts` (838 líneas, cliente axios creado por llamada con `fiscal_credentials.baseUrl`, L211-228), `invoice-electronic.service.ts` (payload L209-278, NC L399-471, emisión async L127-191), `invoice.service.ts` (`createFromSale` L722-800, encola L1111-1122, `retryEmission` L1334-1440), `listeners/sale-completed.listener.ts:39-87`, `electronic-invoice-sync.service.ts:20-44,138-170` (`setInterval` 60 s por réplica + `pg_try_advisory_lock`), `sifen-hmac.util.ts`, `sifen.controller.ts:25-286` (19 proxies), `fiscal-credentials/` (AES-256-GCM con `ENCRYPTION_KEY`, `crypto.util.ts:22-69`; `resolveStrict` L98-125).
- Flujo: `SaleService.createWithPayments` commit → `emitAsync('sale.completed')` (`sale.service.ts:751-752, 1272`) → listener evalúa `AUTO_GENERATE_INVOICES`, sucursal, credenciales → BullMQ → factura `PENDIENTE_ENVIO` → `POST /api/v1/facturas` → `applyExternalStatusSnapshot`.
- Endpoints SIFEN que llama: `/api/v1/facturas` (POST L375-403, GET L405, lista L499), `/facturas/{id}/cancelar` (L425), `/eventos*` (L448-690), `/de/*`, `/ruc/*`, `/kude/{cdc}` (L531), `/emails/*`, `/monitor/*`, `/lotes/*`.
- Auth hacia SIFEN: `X-Access-Key` + **`X-Secret-Key` en claro** (`buildHeaders` L785-799) y además HMAC (`L230-266`). Mandar el secreto anula el HMAC.
- Persistencia: `invoices` (`invoice.entity.ts`): `status DRAFT|ISSUED|CANCELLATION_PENDING|CANCELLATION_FAILED|CANCELLED|VOID` (L19-26), `fiscal_provider LOCAL|ELECTRONIC_API` (L33-36), `cdc` único (L194), `external_transaction_id` único (L206-212), `external_estado`, `external_codigo_respuesta`, `external_sync_payload/response` jsonb (L261-273), `UQ_invoices_sale_id` (L42).
- **Bug**: índice único **global** `idx_invoices_external_transaction_id_unique` (`database/migrations/1761010000000-AddElectronicInvoiceFieldsToInvoices.ts:22-24`) con `transactionId = saleNumber` (`SALE-########`, secuencia **por organización**, `sequence.service.ts:95,168`): la segunda org que emita `SALE-00000001` choca (23505).
- Config `electronicInvoice` en `config/app.config.ts:111-146`: `ELECTRONIC_INVOICE_ENABLED`, `_FALLBACK_TO_LEGACY`, `_REQUIRE_BRANCH_MAPPING`, `_TIMEOUT_MS` (15000), `_SYNC_*`, `AUTO_GENERATE_INVOICES`, `ENCRYPTION_KEY`. **Prod: `ELECTRONIC_INVOICE_ENABLED: "false"`** (`$POS/k8s/backend-configmap.yaml:51`).
- Tests: ~22 specs en `modules/invoicing/**` + `test/invoice-from-sale-auto-generate.e2e-spec.ts`.
- Docs `$POS/docs/integracion-factura-electronica/`: `SIFEN_INTEGRACION.md` (env, HMAC, flujo, endpoints L217-231), `PLAN_MULTITENANT_FISCAL.md:43-96` (una API key por emisor; la plataforma numera; idempotente por `(emisor_id, transaction_id)`; webhooks existen pero el POS hace polling), `swagger.yml` (copia del OpenAPI de SIFEN).

### 2.4 Dominio para pedidos y catálogo
- `Product` (`catalog/domain/entities/product.entity.ts`): `code` único por org (L16), `name, price` (IVA incl.), `barcode, sku, unit, taxId, categoryId, active, sellable, imageUrl, metadata`; `stock` legado sin uso (L58-71). Sin variantes.
- `stock_items` único `(warehouseId, productId)` (`inventory/domain/entities/stock-item.entity.ts:16-47`); `warehouses` **sin `branchId`** (`warehouse.entity.ts:13-58`); vínculo vía `cash_registers.warehouseId/branchId` (`cash-register.entity.ts:13-20`).
- `Sale` (`sales/domain/entities/sale.entity.ts`): `status PENDING|COMPLETED|CANCELLED|REFUNDED` (L22-26), `saleNumber`, `client_sale_id` uuid con unique parcial (L60-79), `branchId, warehouse_id, customerId, userId NOT NULL`, `operationType`, `deliveryStatus/Address/Contact*` (único escritor `sale.service.ts:461`), `source ONLINE|OFFLINE|BACKDATED` varchar (L52-56,191).
- `sale_items` (snapshot nombre/sku/precio/taxRate), `payments` (`CASH|CARD|TRANSFER|CHECK|CREDIT|OTHER`).
- `Customer`: `ruc/dv` y `ciNumber` únicos por org; `phone`/`mobile` **no únicos ni normalizados**; búsqueda `ILIKE` (`customer.service.ts:162`).
- `Branch`: `establishment`, `electronicInvoiceSucursalId`, `autoGenerateInvoices` (`branch.entity.ts:5-68`).
- Endpoints (JWT humano): `GET /products?page&limit≤500&search&warehouseId&stockStatus` (`product.controller.ts:45-57`, `product.service.ts:193-240`, no filtra `sellable`), `GET /products/search/pos?q&warehouseId&limit≤50` (L59-68), `GET /catalog/offline-snapshot` (requiere `posOfflineMode`, `offline-snapshot.controller.ts:29`), `GET /customers/search/pos`, `/customers/ruc/:ruc`, `POST /customers` (`customer.controller.ts:63-122`), `POST /sales/with-payments` (`sale.controller.ts:73-111`), `POST /sales/:id/payments` (L122), `POST /sales/:id/cancel`, `POST /sales/offline-sync`.
- Atomicidad: `createWithPayments` en una `dataSource.transaction` (`sale.service.ts:381-737`): número con `FOR UPDATE`, venta, pagos, caja, stock con lock, promociones; evento tras commit. Venta `PENDING` sin pagos **no descuenta ni reserva stock** (L623-666); exige crédito salvo `posDeferPayment` (L551-556,1700-1710); con pagos exige caja abierta (L502-513).
- Idempotencia: `clientSaleId` en body (`payment.dto.ts:200-205`; `sale.service.ts:251-288,738-749,788-835`). No hay header `Idempotency-Key` ni columnas de referencia externa.
- Eventos: `sale.completed` (L1272), `sale.cancelled` (L1310), `sale.refunded` (`payment.service.ts:380`), in-process, sin outbox ni webhooks (`TODO: webhook` en `timbrado.service.ts:582`).

### 2.5 Faltantes del POS para Nexus (se cubren en S4)
1. Auth m2m por organización (`integration_api_keys`, guard compatible con `resolveContext`, usuario de sistema para `sales.userId`).
2. Endpoint atómico de pedido externo con `channel` + `external_ref` únicos y `Idempotency-Key`; sin caja ni crédito para ventas de canal.
3. Catálogo para canal: `active && sellable`, stock por depósito, paginado ≥500.
4. Cliente por teléfono E.164 (upsert).
5. Consulta/cancelación de pedido por `{channel}/{externalRef}`.
6. Listener saliente a Nexus para ventas de canal (`sale.completed`/`sale.cancelled`).
7. Reemplazo del cliente fiscal: `transactionId` = uuid de venta; cliente OAuth2 a Nexus; retiro de HMAC/secret en claro y del polling por `setInterval`.

## 3. electronico-sifen-backend (SIFEN)

### 3.1 Stack y despliegue
- Java 21, **Spring Boot 3.5.16** (`$SIFEN/pom.xml:10,21`; el `CLAUDE.md` del workspace dice 3.4.3 y está desactualizado), Maven, PG15, Redis 7 (rate limit, nonces HMAC, locks), **Liquibase** (24 changesets, `src/main/resources/db/changelog/`), jjwt, Bucket4j, OpenPDF, ZXing, JAXB.
- Dockerfile: build `maven:3.9.9-eclipse-temurin-21`, runtime `eclipse-temurin:21.0.12_8-jre`, uid 10001, puertos 8081/8082 (`$SIFEN/Dockerfile:1,11,21`).
- CI `$SIFEN/.github/workflows/deploy.yml`: PR → `mvn test` en ubuntu; main → self-hosted: tests, buildx GHCR `sha-<7>`, Secret `sifen-backend-secrets` por kubectl (L121-165), `sed` + commit `[skip ci]` con reintento (L167-180), espera rollout (L183-205), smoke (L207-209). Tag actual `sha-cf82add` en `$SIFEN/k8s/backend-deployment.yaml:33`.
- Convenciones (`/Users/andresvera/Desktop/Proyectos/electronico-sifen/CLAUDE.md`): hexagonal, `emisorId` solo del JWT o API key; recurso ajeno → 404; reglas de acceso solo en `SecurityConfig`; pruebas ≤10.000 Gs y nunca contra `sifen.set.gov.py`.

### 3.2 Autenticación de integradores
- `ApiKeyAuthFilter` (`src/main/java/com/electronico/sifenbackend/infrastructure/security/ApiKeyAuthFilter.java`): `X-Access-Key` (`ak_live_…`/`ak_test_…`) + `X-Secret-Key` (L32-33); orden: lookup (L71) → IP allowlist ≤20 CIDR → 403 `IP_NOT_ALLOWED` (L80) → rate limit → 429 (L93-100) → hash SHA-256 con gracia 24 h tras rotación (L157-181) → `expiresAt` → 401 (L107) → HMAC si `hmacRequired` (L116-123). Principal = `emisorId`, authorities `SCOPE_*` + `ROLE_API_CLIENT` (L138-144).
- `HmacVerifier.java`: `X-Timestamp` (s), `X-Nonce`, `X-Signature` (hex minúsculas); ±300 s (L27); nonce 600 s en Redis (L28,93); canónico `METHOD\nPATH?QUERY\nTS\nNONCE\nhex(body)` (L110-112). **`hmacRequired` forzado si `ambiente="1"`** (`ApiKeyController.java:232-235`). Vectores: `docs/INTEGRACION_API.md:127-139`.
- `api_keys` (`ApiKeyEntity.java`): `emisor_id, access_key, secret_key_hash, hmac_required, scopes (CSV, default FACTURA), expires_at, previous_secret_*, ip_allowlist`. Scopes (`ApiKeyScopes.java:8-25`): `FACTURA, INVOICE_READ, KUDE_READ, EMAIL_SEND, DE_SYNC, TECHNICAL_XML, EVENTOS_LOTE, CONTINGENCIA`. Creación/rotación solo SUPER_ADMIN (`SecurityConfig.java:124`).
- No existe header `Idempotency-Key`; la idempotencia es por `transactionId`.
- Edge: Cloudflare Access/WAF bloquea rutas admin y aplica allowlist de IP de clientes (`docs/CLOUDFLARE.md:32,64,67,88`). Irrelevante para tráfico ClusterIP.

### 3.3 Endpoints para integradores (base interna `http://backend.sifen.svc.cluster.local:8081`)
| Endpoint | Scope | Fuente |
|---|---|---|
| `POST /api/v1/facturas` → `FacturaComercialResponse` | FACTURA | `FacturaController.java:68`; `swagger.yml:451` |
| `GET /api/v1/facturas/{transactionId}` | FACTURA/INVOICE_READ | `FacturaController.java:384` |
| `GET /api/v1/facturas?desde&hasta&estado&cdc&transactionId&page&size≤100` | ídem | `:400` |
| `POST /api/v1/facturas/{transactionId}/cancelar {motivo}` (evento 01; 48 h facturas) | FACTURA | `:474`; `dto/CancelarFacturaRequest.java:3` |
| `GET /api/v1/kude/{cdc}?template=ticket\|full` → PDF (solo APROBADO*) | FACTURA/KUDE_READ | `KudeController.java:67` |
| `POST /api/v1/kude/{cdc}/share-link` → `{url, token, expiresAt}` 24 h | FACTURA | `:98,121` |
| `GET /api/v1/documentos/{cdc}/xml` | DE_SYNC/TECHNICAL_XML | `DocumentoController.java:36` |
| `GET /api/v1/de/{cdc}` (consulta SOAP en vivo) | FACTURA/INVOICE_READ/DE_SYNC | `SifenController.java:132` |
| `POST /api/v1/eventos/simplificado {cdc, tipoEvento 01\|02\|06, motivo}` | FACTURA | `:160` |
| `POST /api/v1/eventos/lote` (≤15), `/eventos/inutilizacion` | EVENTOS_LOTE | `:175,195` |
| `POST /api/v1/de/{cdc}/reenviar`, `/de/contingencia/reenviar` | DE_SYNC/CONTINGENCIA | `:220,229` |
| `GET /api/v1/ruc/{emisorId}/{ruc}`, `POST /ruc/{emisorId}/lote` | FACTURA | `docs/INTEGRACION_API.md:1480` |
| Swagger: `GET /api/v1/docs/swagger.yml` (9.635 líneas en `src/main/resources/static/api/v1/docs/swagger.yml`) | cualquier key | — |
Eventos de conformidad (03/04/05) no soportados (400). Todas las respuestas llevan `X-Trace-Id`.

### 3.4 Request/response de emisión
- `FacturarTransaccionRequest.java:18-41`: obligatorios `transactionId` (≤100), `fechaEmision` (Instant con offset; −720 h…+120 h), `header{moneda, condicionPago CONTADO|CREDITO, formaPago}`, `receptor{tipoDocumento RUC|CI|INNOMINADO…, numeroDocumento, razonSocial, email?}`, `detail[]{codigo, descripcion, cantidad>0, precioUnitario>0 IVA incl., ivaTipo 0|5|10, descuento?, unidadMedida?}`; opcionales `tipoDocumento` (FACTURA|NOTA_CREDITO|NOTA_DEBITO|NOTA_REMISION|AUTOFACTURA), `cdcReferencia`+`motivoEmision` (NC/ND), `sucursalId`, `puntoExpedicionId`, `credito`, `descuentoGlobal`, etc. `emisorId`/`emisor` se ignoran con API key (`FacturaController.java:70-82,116`).
- `FacturaComercialResponse.java:8-26`: `transactionId, emisorId, sucursalId, puntoExpedicionId, establecimiento, puntoExpedicion, numeroDocumento, fechaEmision, cdc, estado, codigoRespuesta, mensajeRespuesta, total, moneda, actualizadoEn, tipoDocumento, cdcReferencia`.
- Ejemplos: `$SIFEN/examples/factura-consumidor-final-innominado.json`, `factura-contado-ruc`, `nota-credito`, `postman_collection.json`.

### 3.5 Sincronía, estados, idempotencia, numeración
- **Síncrono** vía `siRecepDE` (`OperacionSifenService.java:667-697`); saga interna reserva → número (`FOR UPDATE`, `JpaSucursalAdapter.java:207-222`) → CDC/XML → firma → persistencia → envío. Timeout de lectura o duplicado `1001/1002` → consulta CDC y adopta estado; si falla queda `PENDIENTE_ENVIO` (L684-697, 718-763). Timeouts SIFEN connect 15 s / read 30 s / 3 reintentos (`application.yml:99-103`); doc pide cliente >120 s (`INTEGRACION_API.md:748`).
- Contingencia: scheduler cada 5 min, backoff 5/10/20/40 min (`ContingenciaReenvioScheduler.java:31,54`). Lotes solo para XML crudo.
- Estados (`EstadoDocumento.java:3-10`): `PENDIENTE_ENVIO, EN_PROCESO, APROBADO, APROBADO_CON_OBSERVACION, RECHAZADO, CANCELADO`; transiciones en `OperacionSifenService.transicionar` (L776-801).
- Idempotencia (`040-facturacion.yaml:107-121`, `140-idempotencia-payload-hash.yaml`, `FacturaComercialService.java:157-165,221-239,364-396`): `UNIQUE(emisor_id, transaction_id)`; mismo `transactionId`+mismo hash → 200 original; otro payload → 409 `IDEMPOTENCY_CONFLICT`; en vuelo (5 min) → 409 `EMISION_EN_CURSO` + `Retry-After: 30`; reserva huérfana se reanuda con el mismo CDC.
- Numeración y timbrado los asigna el backend (`FacturaComercialService.java:256-263`); el cliente solo elige `sucursalId`/`puntoExpedicionId`. Onboarding por SUPER_ADMIN: `POST /emisores`, `/certificados/{emisorId}` (.p12), `/configuracion-fiscal`, `/api-keys`, webhook (`INTEGRACION_API.md:233-334`); test→prod `/emisores/{id}/solicitar-prod` (L414-444).

### 3.6 Webhooks, límites, errores
- `WebhookController.java:43-94`, `WebhookRequest.java:8-19` (`emisorId, url, secret, eventos[]`); **solo SUPER_ADMIN** (`SecurityConfig.java:142-144`). Eventos `APROBADO|RECHAZADO|CANCELADO|PENDIENTE_ENVIO`; payload `{eventId, evento, emisorId, timestamp, transactionId, cdc, estado, codigoRespuesta, mensajeRespuesta}`; firma `X-Webhook-Signature: sha256=HMAC(secret, X-Webhook-Timestamp + "." + body)`, `X-Webhook-Event`; timeout 5 s; reintentos 1m,5m,15m,1h,6h,24h, máx 10; dedupe por `eventId` (`INTEGRACION_API.md:1863-1978`).
- Rate limit por `(emisorId, accessKey)` default 120 rpm (`application.yml:139-141`), headers `X-RateLimit-*`, 429 `RATE_LIMIT_EXCEEDED` + `Retry-After: 60`. Edge Traefik 30 r/s por IP.
- Errores: formato propio (`ApiErrorBody.java:35-64`): `{timestamp,status,error,code,message,details[],traceId}`; códigos en `ApiExceptionHandler.java` y `INTEGRACION_API.md:2032-2056` (`VALIDATION_ERROR, BUSINESS_RULE, CONFLICT, EMISION_EN_CURSO, IDEMPOTENCY_CONFLICT, PLAN_LIMIT_EXCEEDED, UNAUTHORIZED, API_KEY_EXPIRED, HMAC_INVALID, IP_NOT_ALLOWED, RATE_LIMIT_EXCEEDED, …`).
- **Advertencia**: nunca se emitió con certificado real en sifen-test (`docs/PLAN_FASES.md:163-175`).

## 4. gift-hub (GiftHub)

### 4.1 Stack y despliegue
- npm workspaces `apps/api` (Node 22, Fastify ^5.2, TS strict ESM, zod, Prisma ^6.14, PG16), `apps/admin` (React 19, Vite 6, Tailwind 4), `packages/shared` (`$GH/package.json:4-7`, `$GH/apps/api/package.json:23,35`). Tests vitest. 125 migraciones Prisma, `prisma migrate deploy` en el `CMD` del contenedor (`$GH/apps/api/Dockerfile`).
- CI `$GH/.github/workflows/deploy.yml`: check (typecheck+tests) → build GHCR `ghcr.io/poravv/fidelizacion-central-{api,admin}` → `sed` en `k8s/api.yaml:71`, `k8s/admin.yaml:35`, commit `[skip ci]`. Tag `sha-145c4a4`.
- Argo: `$K8S/argocd/apps/comercio-central.yaml` (`repoURL git@github.com:poravv/fidelizacion-central.git`, path `k8s`, ns `comercio-central`, `prune:false`, `selfHeal:true`). Service `api` ClusterIP 3000 (`$GH/k8s/api.yaml:142-152`). Dominios `cc-api.mindtechpy.net`, `gifthub.mindtechpy.net`. Secret `comercio-central-secrets` creado por `platforms-deploy.yml`.
- Convenciones (`$GH/AGENTS.md`): hexagonal-lite por módulo, env con zod (`apps/api/src/config.ts:14-59`), errores `{ "error": "<español>" }`, logs con prefijo (`[INTEGRACION]`), commits `tipo: descripción`.

### 4.2 WhatsApp
- Meta Cloud API (`whatsapp/infrastructure/graph-whatsapp-sender.ts:7`); webhook `GET/POST /api/webhooks/whatsapp` (`webhook-controller.ts:133,262`) con `x-hub-signature-256`, ruteo por `phone_number_id`; credenciales por tenant cifradas AES-GCM (`schema.prisma:1720`). Máquina de estados `conversation-state-machine.ts` (pasos en `whatsapp/domain/conversation.ts:6-51`); saga por mensaje idempotente por `wamid` con lease (`handle-incoming-whatsapp-message.ts`).

### 4.3 Dominio
- Multi-tenant: `Tenant` (`schema.prisma:1641`, `integrationsEnabled`), `tenant_id` default `current_setting('app.tenant_id')` (`:10-12`, `src/tenancy/tenant-scoped-prisma.ts`).
- `Product` (`:517-575`): `externalId`+`integrationId` únicos por tenant (`:550-571`), `name, description, pricePyg (int), priceLabel, available, stockQty (espejo, no se descuenta, :561), imageKey/imageSourceUrl, category (texto), externalUpdatedAt`; opciones/toppings (`:596-644`); `Integration.managedFields` (`:2034`).
- `Order` (`:646-733`): `orderNumber, publicToken, source (WHATSAPP|INSTAGRAM|WEB…), orderStatus DRAFT|PENDING|CONFIRMED|PREPARING|READY|OUT_FOR_DELIVERY|DELIVERED|CANCELLED` (`orders/domain/admin-order.ts:8`), `paymentStatus PENDING|PAID|REJECTED|REFUNDED`, `paymentMethod EFECTIVO|TRANSFERENCIA|TARJETA_POS|LINK_PAGO|QR|OTRO`, `fulfillmentType DELIVERY|PICKUP`, montos `*Pyg`, dirección/geo. `OrderItem` (`:918`): `productId, productNameSnapshot, unitPricePyg, quantity, lineTotalPyg, selectedOptions`. Transiciones en `orders/domain/order-status.ts:30-64`.
- `Customer` (`:34`): `whatsappId, phoneE164, name, confirmedName, address`; **sin RUC/CI**.
- Creación desde el bot: `create-whatsapp-order.ts:39-70` (revalida `available`, encola `order.created` en la misma transacción `:138`). **No consulta ni reserva stock externo.**

### 4.4 API de integraciones v1 (lo que usa Nexus)
- Base `/api/integrations/v1`, registro `integrations/infrastructure/register-integrations.ts:39-75`, manual `$GH/docs/manual-integracion.md`.
- Auth `Authorization: Bearer gh_live_<prefijo>_<secreto>` (`api-key-guard.ts:45-53`), rotación con 24 h, IP allowlist, 429 por fallos.
- Productos (`integration-products-controller.ts`): `PUT /products/bulk` (≤500, mapeo JSON, `dry_run`, L61), `PATCH /products/:externalId` (L87), `POST /products/:externalId/stock {set|delta}` (L99), `GET /products?updated_since=` (L111), `PUT/DELETE /products/:externalId/image` (L121-140).
- Pedidos (`integration-orders-controller.ts`): `GET /orders/:id` (L38), `POST /orders/:id/status {status, note}` (L44), `POST /orders/:id/paid {reference, method CASH|TRANSFER|CARD|OTHER}` (L58). `Idempotency-Key` obligatoria en pedidos (`IntegrationInboundCall`, `schema.prisma:2068`).
- Webhooks salientes (outbox `IntegrationDelivery`, `:2089`): eventos `order.created, order.paid, order.status_changed, order.cancelled, request.created, ping` (`packages/shared/src/integrations.ts:12`); firma `v1=HMAC-SHA256(secret, "<ts>.<body>")` (`webhook-signature.ts:9`), headers `X-GiftHub-Timestamp`, `X-GiftHub-Delivery`; 8 reintentos ~3,6 días; payload `build-event-payload.ts:42` (items con `external_id`; cliente/entrega solo con `includeCustomer`). **Transporte exige `https://`, bloquea IPs privadas, sin redirects, 10 s** (`https-webhook-transport.ts:26-29`). Despacho cada 15 s (`server.ts:245,1078-1090`), 1 réplica.
- "Modo pull: Gift Hub no consulta tu sistema" (`docs/manual-integracion.md:790`).

### 4.5 Faltantes (mínimos) para Nexus
- `Order.externalOrderId/externalOrderNumber` y aceptarlos en `POST /orders/:id/status` (hoy exige UUID propio y no guarda referencia externa). Opcional: documento del cliente; mapeo de pagos con pérdida (`QR/LINK_PAGO → OTHER/CARD`, `build-event-payload.ts:17`); aviso al cliente solo dentro de 24 h (`send-order-status-notification.ts:113`).

## 5. k8s-platforms (cluster mindtechpy)

- Convenciones: `$K8S/CLAUDE.md` (L6-65) y sección global de `~/.claude/CLAUDE.md`. App-of-apps `argocd/apps/root.yaml:6-22` (`recurse: true`, prune+selfHeal).
- **Todas las apps toman sus manifests del repo de la app (`k8s/`)**; k8s-platforms solo tiene infraestructura (`infra/{cloudflared,edge,gateway,monitoring,cert-manager}`, `ai-platform`, `mcp-k8s`).
- Edge: Traefik v3.2.2 chart 33.2.1 **solo Gateway API** (`argocd/apps/traefik.yaml:29-40`), `Gateway public-gateway` ns `traefik`, listener `web` 8000 `allowedRoutes from: All` (`infra/gateway/gateway.yaml:14-25`); cloudflared remote-managed 2 réplicas (`infra/cloudflared/deployment.yaml:1-40`), regla wildcard `*.mindtechpy.net → traefik.traefik:80`; TLS en Cloudflare. Alta de hostname: HTTPRoute + NetworkPolicy desde `traefik` + CNAME proxied (`docs/networking-traefik-tunnel.md:117-134`).
- Postgres: **uno por app**, Deployment + Service `postgres:5432` + PVC `longhorn` + `PGDATA=.../pgdata` (`$SIFEN/k8s/postgres-deployment.yaml:38-40`); sin operador. Backups: CronJob con `pg_dump -Fc` + verificación >10 KB + rclone crypt a R2 (`$POS/k8s/backup-cronjobs.yaml:55-115`); Secret `backup-r2` creado por `platforms-deploy.yml:784-806` para `BACKUP_NAMESPACES="punto-venta sifen pohapp-backend"`.
- Secrets: solo `kubectl create secret … --dry-run=client -o yaml | kubectl apply` desde CI (`argocd/README.md:66-67`); sin sealed/external-secrets. Repos privados usan Secret `repo-<app>` con deploy key (`platforms-deploy.yml:487-501`); **MindTech-Nexus es público → no hace falta**.
- `ops-runbook.yml`: `delete-pod`, `pg-resetwal`, `run-backup`, `delete-resource`, `restore-db`, `restore-root` (L1-75), runner label `self-hosted`.
- punto-venta (`argocd/apps/punto-venta.yaml:12-33`, manifests `$POS/k8s/`): ns `punto-venta`, Service `backend:3000` ClusterIP, probes `/api/health/live|ready`, HPA 1-4, HTTPRoute `pventa.mindtechpy.net → frontend:80`, Secret `punto-venta-backend-secrets` (keys `DATABASE_PASSWORD, JWT_SECRET, ENCRYPTION_KEY, REDIS_PASSWORD, MINIO_*, MAIL_PASSWORD, OTP_PEPPER`). **NetworkPolicy** `network-policy.yaml:12-26`: ingress solo desde ns `traefik` y pods del mismo ns. Falta `RespectIgnoreDifferences=true` en la Application (desviación del checklist).
- sifen-backend (`argocd/apps/sifen-backend.yaml:15-27`, manifests `$SIFEN/k8s/`): ns `sifen`, Service `backend:8081/8082`, probes actuator en 8082, `runAsUser 10001`, `readOnlyRootFilesystem`, HTTPRoute `electron-sifen.mindtechpy.net` con Middleware rate limit; Postgres `postgres:15.18-alpine`; roles `sifen_user` (Liquibase) / `sifen_app` (runtime). **NetworkPolicy** `network-policy.yaml:41-65`: ingress solo desde `traefik` y `app: frontend` del mismo ns. sifen-dashboard: nginx unprivileged uid 101 en 8080 con proxy `/api/` → `backend:8081` (plantilla para `nexus-console`: `/Users/andresvera/Desktop/Proyectos/electronico-sifen-dashboard/k8s/frontend-{deployment,configmap}.yaml`).
- Observabilidad: kube-prometheus-stack 78.1.0 en `monitoring`, **acepta cualquier ServiceMonitor/PodMonitor sin labels** (`argocd/apps/monitoring.yaml:101-104`); Grafana 12.4 (`metrics.mindtechpy.net`), Loki 2.9 + Promtail DaemonSet (`infra/monitoring/README.md:5-14`). Sin OTel collector. Plantilla ServiceMonitor: `/Users/andresvera/Desktop/Proyectos/zyra-mvp/infra/k8s/40-servicemonitor.yaml`.
- DNS `cluster.local`. URLs para Nexus: `http://backend.punto-venta.svc.cluster.local:3000/api`, `http://backend.sifen.svc.cluster.local:8081`, `http://api.comercio-central.svc.cluster.local:3000`.
- Plantillas a copiar: Application `argocd/apps/sifen-backend.yaml`; Deployment Spring `$SIFEN/k8s/backend-deployment.yaml`; CI `$SIFEN/.github/workflows/deploy.yml`; hooks de migración/backup `accenova-backend/k8s/db-{backup,migrations}-job.yaml`.
