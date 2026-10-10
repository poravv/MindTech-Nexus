# MindTech Nexus

Integrador multi-tenant liviano entre punto-venta, Electrónico SIFEN y GiftHub. Spec: `MINDTECH_NEXUS_ESPECIFICACION_TECNICA.md`; decisiones en `docs/adr/`; plan por slices en `docs/implementation-plan.md`.

## nexus-api

Spring Boot 4.1.1 / Java 21, un módulo Maven hexagonal (`domain` → `application` → `infrastructure`, verificado con ArchUnit), PostgreSQL 16 + Flyway, `JdbcClient`.

Estado (S1): tenants, usuarios de consola, clientes m2m con `client_credentials`, conexiones con credenciales cifradas AES-256-GCM, auditoría, problem+json, health y métricas.

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
- OpenAPI: `/v3/api-docs`, `/swagger-ui.html`. Management (puerto 8081): `/actuator/health/{liveness,readiness}`, `/actuator/prometheus`.

Errores en `application/problem+json` con `type` `https://nexus.mindtechpy.net/problems/<code>`, `code` y `correlationId` (`X-Correlation-Id`).

## Despliegue (cluster mindtechpy, GitOps)

- `k8s/` lo sincroniza Argo CD (Application `nexus` en `k8s-platforms/argocd/apps/nexus.yaml`), namespace `nexus`. Única entrada pública: `https://nexus.mindtechpy.net` → `nexus-console`; la consola hace proxy de `/api/admin/` y `/api/v1/webhooks/` a `nexus-api:8080`.
- `.github/workflows/deploy.yml`: en PR corre tests y build sin push; en `main` publica `ghcr.io/poravv/mindtech-nexus-{api,console}:sha-<7>`, crea el Secret `nexus-secrets` y commitea el tag en `k8s/*-deployment.yaml` (`chore(deploy): nexus sha-<7> [skip ci]`). Rollback = revertir ese commit.
- GitHub Secrets del repo: `NEXUS_DB_NAME`, `NEXUS_DB_USER`, `NEXUS_DB_PASSWORD`, `NEXUS_JWT_SIGNING_KEY`, `NEXUS_MASTER_KEY`, `NEXUS_BOOTSTRAP_ADMIN_EMAIL`, `NEXUS_BOOTSTRAP_ADMIN_PASSWORD`. El job `release` corre en el runner self-hosted del cluster (kubectl ya configurado): el repo necesita ese runner registrado.
- **Primer despliegue**: el tag inicial de `k8s/*-deployment.yaml` no corresponde a una imagen publicada. El primer run de `deploy.yml` en `main` construye ambas imágenes y pinea el tag real; recién después se mergea la Application en `k8s-platforms` para que Argo sincronice. Luego hacer públicos los paquetes GHCR (`k8s-platforms/docs/ghcr-public-packages.md`).
- Backups: CronJob `postgres-backup` (02:30) con dump local en `backup-pvc` y copia cifrada a R2 bajo `nexus/`; el Secret `backup-r2` lo crea `k8s-platforms` (`BACKUP_NAMESPACES`). Probar con `gh workflow run ops-runbook.yml -R poravv/k8s-platforms -f action=run-backup -f namespace=nexus -f target=postgres-backup` y revisar el tamaño del dump.
