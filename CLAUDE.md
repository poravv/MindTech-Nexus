# MindTech Nexus — reglas para agentes

Fuente de verdad: `MINDTECH_NEXUS_ESPECIFICACION_TECNICA.md`, `docs/adr/`, `docs/implementation-plan.md`.

## Regla rectora: sin sobre-ingeniería
- Lo mínimo que resuelve la necesidad actual, preparado para crecer por puertos, no por código especulativo.
- Código simple y transparente: se entiende sin comentarios. Si necesita comentario para explicar el QUÉ, simplificar.
- Nada de interfaces con una sola implementación, factories, configs o parámetros "para el futuro". Excepción: los puertos que la spec exige (identity, queue, rate limiter, conectores).
- Tres líneas parecidas antes que un helper prematuro. Sin código muerto ni TODOs vacíos.

## Arquitectura hexagonal / clean
- `domain`: Java puro, sin Spring ni anotaciones de framework.
- `application`: casos de uso, sagas y puertos.
- `infrastructure`: adaptadores (web, JDBC, HTTP a POS/SIFEN/GiftHub, crypto).
- Dependencias hacia adentro, verificadas con ArchUnit.

## Sagas (flujos que cruzan sistemas)
Compensación explícita por paso, validar el efecto real, punto único de compensación, idempotencia con clave estable (duplicado remoto = éxito), fail-fast de credenciales, prefijo de log estable (`[NEXUS-FISCAL]`, `[NEXUS-ORDER]`), tests por ramas de compensación.

## Seguridad
Nunca secretos en Git, logs ni respuestas. `.env` y `.env.local-stack` son locales (gitignored). `tenant_id` en toda query.

## Git
Commits atómicos `type: descripción`. `git diff --staged` antes de commitear.
