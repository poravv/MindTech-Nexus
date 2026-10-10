# ADR-001 — Monolito modular lean: un deployable, un módulo Maven, hexagonal por paquetes

**Estado:** aceptada (10/10/2026)

## Contexto
La v1.0 proponía `nexus-api` + `nexus-worker` + 12 módulos Maven, Redis y RabbitMQ. El cluster es single-node, hay un tenant piloto por producto y el volumen esperado es de decenas de operaciones por minuto. La prioridad es un MVP terminado, probado y operable por una persona.

## Decisión
- Un único proceso Spring Boot 4.1.1 / Java 21 (`nexus-api`) que contiene API runtime (`/api/v1`), admin API (`/api/admin`), receptores de webhooks y el worker. El worker se activa con `nexus.worker.enabled` (default `true`) para poder separarlo en otro Deployment sin tocar código.
- Un solo módulo Maven con paquetes `com.mindtechpy.nexus.{domain,application,infrastructure}`. Las reglas de dependencia las hace cumplir **ArchUnit** en el build: `domain` sin Spring/Jakarta/Jackson; `application` solo depende de `domain`; `infrastructure` implementa los puertos.
- Persistencia con `JdbcClient` + SQL explícito + Flyway (sin JPA).
- Consola Angular separada (`nexus-console`) servida por nginx, que hace proxy solo de `/api/admin` y `/api/v1/webhooks`.

## Consecuencias
- Menos código, menos pipelines, un solo lugar donde buscar. Un bug en el worker tumba la API: aceptable con 1 réplica y readiness correcta; separación futura trivial por propiedad.
- Las fronteras hexagonales dependen de ArchUnit, no de la compilación. El test es obligatorio y falla el build.
- Si aparece un segundo worker, el claim `SKIP LOCKED` ya es seguro; lo que hay que reimplementar es `RateLimiterPort` (ver ADR-002).
