# ADR-003 — Autenticación propia: OAuth2 client credentials emitido por Nexus y usuarios locales para la consola

**Estado:** aceptada (10/10/2026)

## Contexto
La v1.0 requería Keycloak. Son dos tipos de identidad: sistemas (POS y futuros) y humanos (consola). Keycloak agrega un Deployment, otra base y un realm que operar para 1-3 usuarios y 2-3 clientes.

## Decisión
- **m2m**: `POST /oauth/token` (`client_credentials`, HTTP Basic). JWT HS256 firmado con `NEXUS_JWT_SIGNING_KEY` (256 bits, Secret de K8s), 15 min, claims `sub`, `tenant_id`, `scope`, `typ=client`. Validación con Spring Security OAuth2 Resource Server y `JwtDecoder` local. Clientes en `api_clients` con bcrypt (cost 12), scopes, rotación con gracia 24 h.
- **Consola**: usuarios en `users` (bcrypt cost 12, bloqueo progresivo), login → access JWT 15 min (`typ=user`, `role`, `tenant_id`) + refresh opaco en cookie `HttpOnly; Secure; SameSite=Strict` persistido con hash en `user_sessions` (revocable). Roles `platform_admin`, `tenant_admin`, `operator`, `auditor`, `viewer`.
- `IdentityProviderPort` en `application`; `LocalUserIdentityProvider` es la única implementación v1. OIDC es una implementación futura sin cambios en casos de uso.
- El `tenant_id` sale siempre del token; path/body nunca lo definen.

## Consecuencias
- Cero infraestructura extra; un secreto más que rotar (`NEXUS_JWT_SIGNING_KEY`; rotar invalida tokens de 15 min, aceptable).
- HS256 implica que solo Nexus verifica sus tokens. El POS no valida tokens de Nexus (usa su propia API key para recibir llamadas de Nexus), así que no se necesita RS256/JWKS. Si un tercero debiera validar tokens, se cambia a RS256 en `JwtEncoder/Decoder` sin tocar casos de uso.
- Sin MFA ni SSO en v1; la consola queda detrás de Cloudflare y con rate limit en login.
