# ADR-004 — Ruta fiscal POS → Nexus → SIFEN y retiro directo del adaptador POS → SIFEN

**Estado:** aceptada (10/10/2026)

## Contexto
`punto-venta` tiene un módulo `invoicing/` que llama directo a electronico-sifen (cliente axios de 838 líneas, HMAC, 19 proxies `/sifen/*`, polling por `setInterval`). En producción está apagado (`ELECTRONIC_INVOICE_ENABLED=false`), hay 0 timbrados y 0 clientes usándolo. Tiene además un bug latente: índice único global sobre `external_transaction_id` con `transactionId = saleNumber`, que se repite entre organizaciones. electronico-sifen es síncrono e idempotente por `(emisor, transactionId)`.

## Decisión
- Toda emisión fiscal del POS pasa por Nexus: `POST /api/v1/invoices` (202) + `GET /api/v1/invoices/{sourceRef}` (polling de 60 s con BullMQ repeatable en el POS). `sourceRef = sale.id` (uuid) es la clave idempotente en Nexus y el `transactionId` en SIFEN.
- En Nexus la saga `ISSUE_INVOICE` tiene pasos `SUBMIT → VERIFY (solo tras resultado incierto) → TRACK (solo si quedó pendiente)`. Un DE aprobado nunca se compensa automáticamente; la cancelación es una operación de negocio separada que inicia el POS.
- **El adaptador viejo se elimina en el mismo PR que introduce el nuevo**, sin flag de doble ruta: se borran `electronic-invoice-api.service.ts`, `sifen-hmac.util.ts`, `sifen.controller.ts`, `electronic-invoice-sync.service.ts`, el swagger copiado y sus tests. `ELECTRONIC_INVOICE_ENABLED` se mantiene como único interruptor. Rollback = revertir el commit.
- `fiscal_credentials` pasa a guardar `client_id` / `client_secret_enc` de Nexus; `NEXUS_BASE_URL` es global (ClusterIP).

## Consecuencias
- Se elimina el envío de `X-Secret-Key` en claro y el HMAC del POS: la credencial SIFEN vive solo en Nexus, cifrada.
- Se corrige el bug del unique global al usar un uuid como `transactionId`.
- No hay "fallback al adaptador viejo" ante timeouts: la recuperación es consultar estado por `sourceRef`, nunca reemitir con otra clave.
- Primera emisión real contra sifen-test queda como hito explícito antes de producción (SIFEN nunca emitió con certificado real).
