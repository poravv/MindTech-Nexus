# ADR-007 — GiftHub ↔ POS: Nexus actúa como socio de la API de integraciones v1 de GiftHub

**Estado:** aceptada (10/10/2026)

## Contexto
GiftHub ya tiene una API de integraciones completa pero invertida respecto a la idea inicial: el socio **empuja** catálogo y stock (`PUT /api/integrations/v1/products/bulk`, `/stock`), GiftHub **avisa** pedidos por webhook firmado (`order.created`, `order.paid`, `order.cancelled`) y el socio devuelve estados (`POST /orders/:id/status`, `/orders/:id/paid`). GiftHub nunca consulta a nadie y su transporte de webhooks exige `https` y bloquea IPs privadas. El POS, en cambio, no tiene nada: ni auth m2m, ni pedido externo, ni catálogo de canal.

## Decisión
- No se construye un cliente saliente en GiftHub. Nexus se registra como integración de GiftHub (API key `gh_live_*` + webhook `https://nexus.mindtechpy.net/api/v1/webhooks/gifthub/{connectionId}` con `includeCustomer`).
- **Catálogo**: operación programada `CATALOG_SYNC` por integración (default 5 min): lee `GET /api/integration/catalog` del POS (nuevo) y hace `PUT /products/bulk` en GiftHub. Full sync por corrida; `available = active && sellable && stock > 0`.
- **Pedidos**: webhook `order.created` → saga `CREATE_ORDER`: `RESOLVE_CUSTOMER` (POS upsert por teléfono E.164, nuevo) → `CREATE_ORDER` (POS `POST /api/integration/orders`, atómico, nuevo; venta `PENDING` con `channel='GIFTHUB'`, `external_ref=order.id`) → `NOTIFY_GIFTHUB` (`CONFIRMED` + número de venta). Compensación única: si el POS rechaza (stock/producto), GiftHub recibe `CANCELLED` con motivo.
- **Estados de vuelta**: el POS, al completar o cancelar una venta de canal, llama a Nexus (`POST /api/v1/orders/GIFTHUB/{externalRef}/status`); Nexus marca `paid` + `DELIVERED` o `CANCELLED` en GiftHub.
- Único cambio en GiftHub: guardar `externalOrderId`/`externalOrderNumber` en `Order` y aceptarlos en `/orders/:id/status`.
- Sin reserva de stock en v1: el POS valida stock al crear y descuenta al completar (misma regla que sus ventas `PENDING`).

## Consecuencias
- El esfuerzo se concentra en el POS (slice S4) y en Nexus; GiftHub cambia una migración y un endpoint.
- El pedido se confirma en el bot antes de que el POS lo acepte (GiftHub ya respondió al cliente); si el POS rechaza, el cliente recibe la cancelación con motivo. Aceptado para v1; la reserva previa queda en Evolución.
- Nexus necesita un endpoint público (`/api/v1/webhooks/*`) expuesto por nginx de la consola; se protege con HMAC, tolerancia de reloj, dedupe por `X-GiftHub-Delivery` y rate limit por conexión.
