# ADR-002 — Cola de trabajos, idempotencia y DLQ en PostgreSQL; rate limit en memoria

**Estado:** aceptada (10/10/2026)

## Contexto
La v1.0 exigía RabbitMQ (quorum queues) y Redis (token bucket Lua). En single-node ninguno aporta HA, y suman dos sistemas que operar y respaldar. Postgres ya es obligatorio para el estado de las operaciones.

## Decisión
- Tabla `operations` como cola durable: el worker reclama con `SELECT … FOR UPDATE SKIP LOCKED` + `UPDATE … RETURNING` en una transacción corta, ejecuta el paso **fuera** de la transacción y persiste el resultado en otra. Lease `locked_until` de 10 min y job de rescate para workers caídos.
- Idempotencia en la misma tabla: `UNIQUE(tenant_id, kind, idempotency_key)` + `request_hash` (SHA-256 canónico). Mismo hash → se devuelve el estado existente; distinto → 409.
- DLQ = `status='DEAD'` + `dead_reason`; replay desde la consola conserva la clave idempotente y reinicia `attempts`.
- Retries con backoff exponencial + jitter y clasificación de errores por conector (`TRANSIENT`, `RATE_LIMITED`, `UNCERTAIN`, `REJECTED`, `CONFLICT`, `IN_PROGRESS`, `AUTH`).
- Rate limit (token bucket) y concurrencia (`maxInFlight`) por integración **en memoria**, detrás de `RateLimiterPort`. Techo documentado: válido para 1 réplica.
- Puertos `JobQueuePort` y `RateLimiterPort` con implementaciones Postgres/memoria; RabbitMQ/Redis son otras implementaciones.

## Consecuencias
- Un solo sistema de datos: backup y restore cubren cola y estado juntos (sin outbox/inbox entre dos stores).
- Throughput limitado por polling (500 ms) y por Postgres: suficiente para cientos de ops/min. Si se necesita más o 2+ réplicas, primero se mueve `RateLimiterPort` a Postgres (`UPDATE … RETURNING` sobre una tabla de buckets) y recién después se evalúa RabbitMQ.
- Las operaciones `RUNNING` sobreviven reinicios gracias al lease; una llamada HTTP en vuelo al morir el pod se resuelve con el paso `VERIFY` del saga.
