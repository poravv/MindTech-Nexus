# ADR-005 — Multi-tenancy: una base compartida, `tenant_id` en todo, tenant derivado solo del token

**Estado:** aceptada (10/10/2026)

## Contexto
Cada sistema integrado tiene su propio concepto de tenant: `organizations` en el POS, `emisorId` (una API key por emisor) en SIFEN, `Tenant` + API key en GiftHub. Nexus debe correlacionarlos sin que un tenant pueda operar sobre otro.

## Decisión
- Un tenant Nexus = una organización del POS = un emisor SIFEN = un tenant GiftHub. La correlación vive en `connections` (una conexión por conector y tenant, con sus credenciales cifradas); no existe un "id remoto" suelto.
- Todas las tablas de negocio llevan `tenant_id NOT NULL`; todos los `UNIQUE` lo incluyen; todos los repositorios reciben `tenantId` como primer argumento y lo ponen en el `WHERE`. Sin RLS en v1 (Evolución).
- El `tenant_id` sale del JWT (claim `tenant_id`) tanto para clientes m2m como para usuarios. `platform_admin` tiene `tenant_id` nulo y elige tenant explícitamente en la admin API (`?tenantId=`), lo que queda auditado.
- Los webhooks entrantes resuelven el tenant por `{connectionId}` en el path y verifican la firma con el secreto de **esa** conexión antes de cualquier otra cosa.
- Un recurso de otro tenant responde 404 (como inexistente), nunca 403, para no filtrar existencia.

## Consecuencias
- Aislamiento por disciplina de código + tests negativos de IDOR obligatorios en runtime y admin (S1).
- Onboarding de un tenant = crear tenant, cliente m2m, tres conexiones y las integraciones desde la consola; nada en código ni en Git.
- Métricas Prometheus sin label `tenant_id` (cardinalidad); el detalle por tenant está en DB y logs.
