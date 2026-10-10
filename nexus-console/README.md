# nexus-console

Consola de administración de MindTech Nexus (spec §12): Angular 22.2 standalone con signals, servida por nginx-unprivileged en el puerto 8080.

## Desarrollo local

Requiere Node `^22.22.3` o `^24.15.0` y el backend (`nexus-api`) corriendo; el `compose.yaml` del repo lo publica en `localhost:8090`.

```bash
npm ci
npm start          # http://localhost:4200, proxy de /api/admin y /api/v1/webhooks a localhost:8090 (proxy.conf.json)
npm test           # Vitest: interceptor de refresh y guards por rol
npm run build      # build de producción en dist/nexus-console/browser
```

El primer ingreso con el admin bootstrap (`NEXUS_BOOTSTRAP_ADMIN_EMAIL/PASSWORD` del `.env`) obliga a cambiar la contraseña.

## Imagen

```bash
docker build -t nexus-console .
docker run -p 8082:8080 --network mindtech-nexus_default nexus-console
```

| Variable | Default | Uso |
|---|---|---|
| `NEXUS_API_UPSTREAM` | `http://nexus-api:8080` | Destino del proxy de `/api/admin/` y `/api/v1/webhooks/` |

`nginx.conf` es una plantilla que la imagen renderiza con envsubst al arrancar en `/etc/nginx/conf.d`. Con `readOnlyRootFilesystem` hay que montar un `emptyDir` en `/etc/nginx/conf.d` y en `/tmp`.

nginx solo expone la SPA, `/api/admin/` y `/api/v1/webhooks/` (el resto de `/api/` responde 404), limita el body a 256 KB y agrega CSP estricta con nonce por request (`sub_filter` sobre `index.html`), `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options` y `frame-ancestors 'none'`.

## Estructura

- `src/app/core/`: sesión (`AuthService`, interceptor Bearer + refresh por cookie HttpOnly, guards), roles, tenant activo, mensajes de error problem+json.
- `src/app/api/`: un servicio HTTP por recurso de la admin API; si cambia un nombre de campo, se ajusta acá.
- `src/app/shared/ui/`: componentes usados en más de una pantalla (badge de estado, modal, confirmación, paginador, avisos).
- `src/app/features/`: una carpeta por pantalla, cargadas con lazy loading.
- `src/styles/_tokens.scss`: design tokens (modo claro/oscuro).

La UI oculta las acciones que el rol no permite; quien autoriza es siempre el backend.
