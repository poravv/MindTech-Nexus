# ADR-006 — Secretos: Secret de K8s creado por CI + credenciales de conexión cifradas AES-GCM en DB

**Estado:** aceptada (10/10/2026)

## Contexto
El cluster no tiene External Secrets, Vault ni sealed-secrets; la convención es que el CI cree los Secrets con `kubectl create secret … --dry-run=client -o yaml | kubectl apply`. Las credenciales de SIFEN, POS y GiftHub son por tenant y se dan de alta desde la consola, no en despliegues.

## Decisión
- **Secretos de plataforma** (pocos, estables) en el Secret `nexus-secrets` creado por `deploy.yml` desde GitHub Secrets: `NEXUS_DB_NAME`, `NEXUS_DB_USER`, `NEXUS_DB_PASSWORD`, `NEXUS_MASTER_KEY` (32 bytes base64), `NEXUS_JWT_SIGNING_KEY`, `NEXUS_BOOTSTRAP_ADMIN_EMAIL`, `NEXUS_BOOTSTRAP_ADMIN_PASSWORD`.
- **Credenciales de conexión** (por tenant, cambian desde la consola) en `connections.credentials_enc`: JSON cifrado con AES-256-GCM, clave `NEXUS_MASTER_KEY`, nonce aleatorio de 12 bytes por registro, AAD = `tenant_id:connection_id` (un registro no se puede trasplantar a otra conexión). Implementación `AesGcmSecretCipher` detrás de `SecretCipherPort`.
- Se descarta "un Secret de K8s por tenant" porque obligaría a tocar CI/Git por cada onboarding y a dar permisos de K8s al proceso.
- La API nunca devuelve credenciales; "probar conexión" solo informa éxito/fallo. Logs con masking de headers y campos sensibles.
- Rotación de `NEXUS_MASTER_KEY` (implementada 10/10/2026, reemplaza el comando `nexus recrypt --old --new` previsto): `NEXUS_MASTER_KEY_PREVIOUS` opcional en `nexus-secrets` (GitHub Secret opcional en `deploy.yml`; el Deployment lo toma por `envFrom`). Mientras está configurada, `open` prueba la clave actual y después la anterior, y `seal` usa siempre la actual; al arrancar, `RecryptCredentials` recifra con la actual cada fila que todavía abre solo con la anterior. Es idempotente (lo ya recifrado se saltea), por fila con compare-and-set (una credencial editada desde la consola mientras tanto no se pisa), auditado por conexión en su tenant (`connection.credentials-recrypted`) y sin pérdida: una fila que ninguna clave abre queda intacta, se audita `connection.recrypt-failed` y el log `[NEXUS-RECRYPT]` la reporta. Procedimiento en el README ("Rotar la master key"): cargar la clave actual como `NEXUS_MASTER_KEY_PREVIOUS` y la nueva como `NEXUS_MASTER_KEY` a la vez, desplegar/reiniciar el pod, verificar `unreadable=0` y recién entonces quitar `NEXUS_MASTER_KEY_PREVIOUS`. Se descartó un Job aparte con `--old --new`: el arranque con dos claves no deja ventana en la que las credenciales no se puedan leer.
- Se descartó una transacción única para todas las filas: el compare-and-set por fila es reintentable (el próximo arranque sigue donde quedó) y no bloquea `connections`.

## Consecuencias
- Un compromiso de la DB sin la master key no expone credenciales; un compromiso del pod sí (igual que cualquier alternativa sin HSM).
- La master key está en etcd como Secret, igual que el resto de la plataforma; el cifrado at-rest de etcd es responsabilidad del cluster.
- Backups de la DB no contienen secretos en claro y se pueden restaurar en otro cluster solo con la misma master key: un backup previo a una rotación necesita la clave anterior (restaurarlo con ella como `NEXUS_MASTER_KEY_PREVIOUS` lo recifra al arrancar).
