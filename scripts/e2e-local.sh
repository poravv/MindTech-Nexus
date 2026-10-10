#!/usr/bin/env bash
# E2E local de MindTech Nexus contra los stacks reales de punto-venta (POS), electronico-sifen (modo mock) y
# GiftHub corriendo en Docker (README → "E2E local"). Sale ≠ 0 en el primer escenario que falla.
#
#   ./scripts/e2e-local.sh                       # todas las fases
#   ./scripts/e2e-local.sh setup fiscal          # solo esas (setup corre siempre: es idempotente)
#
# Credenciales: de .env.local-stack (gitignored). Las que el script crea (cliente m2m de Nexus, API key del POS,
# integración de GiftHub) se agregan ahí la primera vez; nunca se imprimen.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=.env.local-stack
NEXUS=${NEXUS_URL:-http://localhost:8090}
POS=${POS_URL:-http://localhost:3001/api}
GH=${GIFTHUB_URL:-http://localhost:3000}
# Cómo ven los contenedores a los otros stacks (puertos publicados en el host). La allowlist del perfil local de
# Nexus incluye host.docker.internal; GiftHub necesita WEBHOOKS_ALLOW_LOCAL_HTTP=true para llamar a Nexus por http.
NEXUS_FROM_DOCKER=http://host.docker.internal:8090
POS_FROM_DOCKER=http://host.docker.internal:3001
SIFEN_FROM_DOCKER=http://host.docker.internal:8083
GH_FROM_DOCKER=http://host.docker.internal:3000
SIFEN_BACKEND=electronico-sifen-backend
SIFEN_DB=electronico-sifen-db
POS_DB=punto-venta-postgres
GH_DB=tabey-db
TENANT_SLUG=e2e-burger
SIFEN_BRANCH=SUC-001
SIFEN_POINT=default

[ -f "$ENV_FILE" ] || { echo "Falta $ENV_FILE"; exit 1; }
set -a; . "./$ENV_FILE"; set +a
PHASES=${*:-fiscal catalog order ops}

step() { printf '\n== %s\n' "$*"; }
ok() { printf '  ✅ %s\n' "$*"; }
die() { printf '  ❌ %s\n' "$*" >&2; exit 1; }

# Guarda una credencial creada por el script (solo si no estaba) sin imprimirla.
remember() {
  grep -q "^$1=" "$ENV_FILE" || printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"
  export "$1=$2"
}

# req METHOD URL TOKEN [BODY] [curl args...] → deja la respuesta en BODY y el código en STATUS.
req() {
  local method=$1 url=$2 token=$3 body=${4:-} out
  shift $(( $# < 4 ? $# : 4 ))
  local args=(-sS -X "$method" -w '\n%{http_code}' -H 'Content-Type: application/json')
  [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
  [ -n "$body" ] && args+=(--data "$body")
  out=$(curl "${args[@]}" "$@" "$url")
  STATUS=${out##*$'\n'}
  BODY=${out%$'\n'*}
}

# expect "200 201" "descripción": falla mostrando el cuerpo (sin secretos: los cuerpos de error no los traen).
expect() {
  case " $1 " in *" $STATUS "*) ;; *) die "$2 → HTTP $STATUS: $(printf '%s' "$BODY" | head -c 400)";; esac
}

# wait_for "descripción" segundos comando...: reintenta cada 3 s hasta que el comando salga 0.
wait_for() {
  local desc=$1 timeout=$2 start=$SECONDS
  shift 2
  until "$@"; do
    (( SECONDS - start > timeout )) && die "timeout ($timeout s): $desc"
    sleep 3
  done
}

sql() { docker exec -i "$1" psql -qAt -U "$2" -d "$3" -c "$4"; }
sifen_sql() { docker exec -i "$SIFEN_DB" sh -c 'psql -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<< "$1"; }
pos_sql() { sql "$POS_DB" crm crmdb "$1"; }
gh_sql() { sql "$GH_DB" tabey tabey "$1"; }

# ---------------------------------------------------------------- sesiones
login_all() {
  req POST "$NEXUS/api/admin/auth/login" "" "{\"email\":\"$NEXUS_E2E_ADMIN_EMAIL\",\"password\":\"$NEXUS_E2E_ADMIN_PASSWORD\"}"
  expect 200 "login Nexus"; NTOK=$(jq -r .accessToken <<< "$BODY")
  req POST "$POS/auth/login" "" "{\"email\":\"$POS_TENANT_ADMIN_EMAIL\",\"password\":\"$POS_TENANT_ADMIN_PASSWORD\"}"
  expect "200 201" "login POS"; PTOK=$(jq -r .accessToken <<< "$BODY"); POS_USER_ID=$(jq -r .user.id <<< "$BODY")
  req POST "$POS/auth/step-up" "$PTOK" "{\"password\":\"$POS_TENANT_ADMIN_PASSWORD\"}"
  expect "200 201" "step-up POS"; PSTEP=$(jq -r .stepUpToken <<< "$BODY")
  req POST "$GH/api/auth/login" "" "{\"email\":\"$GIFTHUB_OWNER_EMAIL\",\"password\":\"$GIFTHUB_OWNER_PASSWORD\"}"
  expect 200 "login GiftHub"; GTOK=$(jq -r .token <<< "$BODY"); GH_TENANT=$(jq -r .user.tenantId <<< "$BODY")
}

nexus() { req "$1" "$NEXUS$2" "$NTOK" "${3:-}"; }
pos() { req "$1" "$POS$2" "$PTOK" "${3:-}" -H "x-step-up-token: $PSTEP"; }
gh() { req "$1" "$GH$2" "$GTOK" "${3:-}"; }

# Upsert de conexión de Nexus por nombre; siempre reescribe base URL y credenciales.
upsert_connection() {
  local connector=$1 name=$2 base=$3 credentials=$4 settings=${5:-'{}'} id
  nexus GET "/api/admin/connections?tenantId=$TENANT"; expect 200 "listar conexiones"
  id=$(jq -r --arg n "$name" '.[] | select(.name == $n) | .id' <<< "$BODY")
  local body="{\"connector\":\"$connector\",\"name\":\"$name\",\"baseUrl\":\"$base\",\"credentials\":$credentials,\"settings\":$settings}"
  if [ -z "$id" ]; then
    nexus POST "/api/admin/connections?tenantId=$TENANT" "$body"; expect 201 "crear conexión $name"
  else
    nexus PUT "/api/admin/connections/$id?tenantId=$TENANT" "$body"; expect 200 "actualizar conexión $name"
  fi
  jq -r .id <<< "$BODY"
}

upsert_integration() {
  local flow=$1 code=$2 source=$3 target=$4 policy=${5:-null} id body
  nexus GET "/api/admin/integrations?tenantId=$TENANT"; expect 200 "listar integraciones"
  id=$(jq -r --arg c "$code" '.[] | select(.code == $c) | .id' <<< "$BODY")
  body="{\"flow\":\"$flow\",\"code\":\"$code\",\"sourceConnectionId\":$source,\"targetConnectionId\":\"$target\",\"policy\":$policy}"
  if [ -z "$id" ]; then
    nexus POST "/api/admin/integrations?tenantId=$TENANT" "$body"; expect 201 "crear integración $code"
    id=$(jq -r .id <<< "$BODY")
  else
    nexus PUT "/api/admin/integrations/$id?tenantId=$TENANT" "$(jq -c 'del(.flow)' <<< "$body")"; expect 200 "actualizar integración $code"
  fi
  nexus GET "/api/admin/integrations/$id?tenantId=$TENANT"
  if [ "$(jq -r .state <<< "$BODY")" != ENABLED ]; then
    nexus POST "/api/admin/integrations/$id/state?tenantId=$TENANT" '{"state":"ENABLED","reason":"e2e: estado inicial"}'
    expect 200 "habilitar integración $code"
  fi
  echo "$id"
}

# ---------------------------------------------------------------- 1. alta
setup() {
  step "1. Alta: tenant, cliente m2m, conexiones e integraciones"
  login_all
  nexus GET /api/admin/tenants; expect 200 "listar tenants"
  TENANT=$(jq -r --arg s "$TENANT_SLUG" '.[] | select(.slug == $s) | .id' <<< "$BODY")
  if [ -z "$TENANT" ]; then
    nexus POST /api/admin/tenants "{\"slug\":\"$TENANT_SLUG\",\"name\":\"Hamburguesería Prueba (E2E)\"}"; expect 201 "crear tenant"
    TENANT=$(jq -r .id <<< "$BODY")
  fi
  # El secreto m2m solo se ve al crearlo: si se perdió el .env.local-stack, se rota.
  if [ -z "${NEXUS_E2E_POS_CLIENT_ID:-}" ]; then
    nexus POST "/api/admin/api-clients?tenantId=$TENANT" '{"name":"POS Hamburguesería","scopes":["fiscal:issue","fiscal:read","operations:read","orders:write"]}'
    expect 201 "crear cliente m2m"
    remember NEXUS_E2E_POS_CLIENT_ID "$(jq -r .client.clientId <<< "$BODY")"
    remember NEXUS_E2E_POS_CLIENT_SECRET "$(jq -r .clientSecret <<< "$BODY")"
  fi
  ok "tenant $TENANT_SLUG y cliente m2m del POS"

  # POS: punto de expedición de Casa Central mapeado al de SIFEN, cliente m2m de Nexus y API key de integración.
  pos GET /branches; expect 200 "sucursales POS"
  BRANCH=$(jq -r --arg s "$SIFEN_BRANCH" '(if type == "array" then . else .data end)[] | select(.electronicInvoiceSucursalId == $s) | .id' <<< "$BODY")
  [ -n "$BRANCH" ] || die "ninguna sucursal del POS mapeada a $SIFEN_BRANCH"
  pos GET /timbrados; expect 200 "timbrados POS"
  local timbrado; timbrado=$(jq -r --arg b "$BRANCH" '[(if type == "array" then . else .data end)[] | select(.branchId == $b and .isActive)][0].id' <<< "$BODY")
  pos GET /timbrados/puntos-expedicion; expect 200 "puntos POS"
  local punto; punto=$(jq -r --arg t "$timbrado" '[(if type == "array" then . else .data end)[] | select(.timbradoId == $t and .isActive)][0].id' <<< "$BODY")
  pos PUT "/timbrados/puntos-expedicion/$punto" "{\"electronicInvoicePuntoExpedicionId\":\"$SIFEN_POINT\"}"; expect 200 "mapear punto"
  pos GET /warehouses; expect 200 "depósitos POS"
  WAREHOUSE=$(jq -r '(if type == "array" then . else .data end)[] | select(.name == "Depósito Principal") | .id' <<< "$BODY")
  pos PUT /fiscal-credentials "{\"clientId\":\"$NEXUS_E2E_POS_CLIENT_ID\",\"clientSecret\":\"$NEXUS_E2E_POS_CLIENT_SECRET\"}"
  expect "200 201" "credenciales fiscales del POS"
  pos POST /fiscal-credentials/test; expect "200 201" "probar Nexus desde el POS"
  [ "$(jq -r .status <<< "$BODY")" = ok ] || die "POS → Nexus: $(jq -c . <<< "$BODY")"
  pos PUT /invoicing/mode '{"mode":"ELECTRONIC"}'; expect "200 201" "modo ELECTRONIC"
  if [ -z "${POS_E2E_API_KEY:-}" ]; then
    pos POST /integration-api-keys "{\"name\":\"Nexus E2E\",\"scopes\":[\"catalog:read\",\"customers:write\",\"orders:write\"],\"systemUserId\":\"$POS_USER_ID\",\"defaultBranchId\":\"$BRANCH\",\"defaultWarehouseId\":\"$WAREHOUSE\"}"
    expect "200 201" "API key de integración del POS"
    remember POS_E2E_API_KEY "$(jq -r .apiKey <<< "$BODY")"
  fi
  ok "POS: modo ELECTRONIC, cliente Nexus probado, API key de integración"

  # GiftHub: interruptor de integraciones del negocio (lo maneja un super admin con código por mail: en local, DB).
  gh_sql "UPDATE tenants SET integrations_enabled = true WHERE id = '$GH_TENANT'" >/dev/null
  if [ -z "${GIFTHUB_E2E_API_KEY:-}" ]; then
    gh POST /api/admin/integrations '{"name":"MindTech Nexus (E2E)"}'; expect 201 "crear integración GiftHub"
    remember GIFTHUB_E2E_INTEGRATION_ID "$(jq -r .integration.id <<< "$BODY")"
    remember GIFTHUB_E2E_API_KEY "$(jq -r .apiKey <<< "$BODY")"
    remember GIFTHUB_E2E_WEBHOOK_SECRET "$(jq -r .webhookSecret <<< "$BODY")"
  fi

  CONN_SIFEN=$(upsert_connection SIFEN "SIFEN local" "$SIFEN_FROM_DOCKER" \
    "{\"accessKey\":\"$SIFEN_API_ACCESS_KEY\",\"secretKey\":\"$SIFEN_API_SECRET_KEY\",\"webhookSecret\":\"${SIFEN_E2E_WEBHOOK_SECRET:-sin-webhook}\"}")
  CONN_POS=$(upsert_connection POS "POS Hamburguesería" "$POS_FROM_DOCKER" "{\"apiKey\":\"$POS_E2E_API_KEY\"}")
  CONN_GH=$(upsert_connection GIFTHUB "GiftHub mindtechpy" "$GH_FROM_DOCKER" \
    "{\"apiKey\":\"$GIFTHUB_E2E_API_KEY\",\"webhookSecret\":\"$GIFTHUB_E2E_WEBHOOK_SECRET\"}")
  INT_FISCAL=$(upsert_integration FISCAL fiscal null "$CONN_SIFEN")
  INT_ORDER=$(upsert_integration ORDER pedidos-gifthub "\"$CONN_GH\"" "$CONN_POS")
  INT_CATALOG=$(upsert_integration CATALOG catalogo-gifthub "\"$CONN_POS\"" "$CONN_GH" '{"scheduleEverySeconds":3600}')
  gh PATCH "/api/admin/integrations/$GIFTHUB_E2E_INTEGRATION_ID" \
    "{\"enabled\":true,\"webhookUrl\":\"$NEXUS_FROM_DOCKER/api/v1/webhooks/gifthub/$CONN_GH\",\"webhookEvents\":[\"order.created\",\"order.cancelled\"],\"includeCustomer\":true}"
  expect 200 "webhook de GiftHub → Nexus"
  ok "conexiones SIFEN/POS/GiftHub e integraciones FISCAL, ORDER, CATALOG"

  for conn in "$CONN_POS" "$CONN_SIFEN" "$CONN_GH"; do
    nexus POST "/api/admin/connections/$conn/test?tenantId=$TENANT"; expect 200 "probar conexión"
    [ "$(jq -r .ok <<< "$BODY")" = true ] || die "Probar conexión: $(jq -r .detail <<< "$BODY")"
    ok "Probar conexión: $(jq -r .detail <<< "$BODY")"
  done
}

# ---------------------------------------------------------------- helpers POS
# Producto del POS por código (lo crea si falta) con stock exacto en el depósito principal; deja su id en PRODUCT.
ensure_product() {
  local code=$1 name=$2 price=$3 stock=$4
  pos GET "/products?search=$code&page=1&limit=5"; expect 200 "buscar producto $code"
  PRODUCT=$(jq -r --arg c "$code" '[(if type == "array" then . else .data end)[] | select(.code == $c)][0].id // empty' <<< "$BODY")
  if [ -z "$PRODUCT" ]; then
    pos POST /products "{\"code\":\"$code\",\"name\":\"$name\",\"type\":\"PRODUCT\",\"price\":$price,\"trackInventory\":true}"
    expect "200 201" "crear producto $code"; PRODUCT=$(jq -r .id <<< "$BODY")
  else
    pos PUT "/products/$PRODUCT" '{"active":true}'; expect 200 "activar producto $code"
  fi
  pos POST /stock/adjust "{\"warehouseId\":\"$WAREHOUSE\",\"productId\":\"$PRODUCT\",\"newQuantity\":$stock}"
  if [ "$STATUS" = 400 ] && grep -q "same as current" <<< "$BODY"; then
    STATUS=200
  elif [ "$STATUS" = 404 ]; then  # producto sin stock inicial en el depósito
    pos POST /stock/movement "{\"warehouseId\":\"$WAREHOUSE\",\"productId\":\"$PRODUCT\",\"type\":\"IN\",\"reason\":\"INITIAL\",\"quantity\":$stock,\"unitCost\":1000}"
  fi
  expect "200 201" "stock de $code"
}

ensure_cash_session() {
  pos GET /cash-registers; expect 200 "cajas"
  local register; register=$(jq -r --arg b "$BRANCH" '[(if type == "array" then . else .data end)[] | select(.branchId == $b)][0].id' <<< "$BODY")
  pos GET "/cash-registers/$register/active-session"
  if [ "$STATUS" != 200 ] || [ -z "$(jq -r '.id // empty' <<< "$BODY" 2>/dev/null)" ]; then
    pos POST /cash-registers/sessions/open "{\"cashRegisterId\":\"$register\",\"branchId\":\"$BRANCH\",\"openingAmount\":0}"
    expect "200 201" "abrir caja"
  fi
  CASH_SESSION=$(jq -r .id <<< "$BODY")
}

# Venta; deja su id en SALE. Sin pagos y con vencimiento = venta a crédito.
pos_sale() {
  local items=$1 payments=$2 extra=${3:-}
  pos POST /sales/with-payments "{\"branchId\":\"$BRANCH\",\"warehouseId\":\"$WAREHOUSE\",\"cashSessionId\":\"$CASH_SESSION\",\"items\":$items,\"payments\":$payments$extra}"
  expect "200 201" "venta en el POS"; SALE=$(jq -r .id <<< "$BODY")
}

sale_invoice_is() {  # sale estado → deja la factura en INVOICE (GET /sales/:id solo trae id, número y CDC)
  pos GET "/sales/$1"; [ "$STATUS" = 200 ] || return 1
  local id; id=$(jq -r '.invoice.id // empty' <<< "$BODY")
  [ -n "$id" ] || return 1
  pos GET "/invoices/$id"; [ "$STATUS" = 200 ] || return 1
  INVOICE=$BODY
  [ "$(jq -r .externalEstado <<< "$INVOICE")" = "$2" ] && return 0
  pos POST "/invoices/$id/sync-fiscal-status"
  return 1
}

sifen_documents() { sifen_sql "SELECT count(*) FROM documentos_electronicos WHERE transaction_id = '$1'"; }

nexus_operation() {  # sourceRef/clave → deja la operación más reciente en OP
  nexus GET "/api/admin/operations?tenantId=$TENANT&q=$1&size=5"; expect 200 "operaciones de Nexus"
  OP=$(jq -c '.[0] // empty' <<< "$BODY")
}

m2m_token() {
  M2M=$(curl -sS -u "$NEXUS_E2E_POS_CLIENT_ID:$NEXUS_E2E_POS_CLIENT_SECRET" -d grant_type=client_credentials "$NEXUS/oauth/token" | jq -r .access_token)
}

# ---------------------------------------------------------------- 2. fiscal
# SIFEN (mock) a veces deja el DE en PENDIENTE_ENVIO y lo reenvía cada 5 min; Nexus consulta a 1 m/5 m y su
# webhook adelanta la consulta. Por eso las esperas fiscales son de hasta 12 min.
phase_fiscal() {
  step "2. Fiscal: POS → Nexus → SIFEN"
  ensure_cash_session
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 500
  local hamb=$PRODUCT
  pos_sale "[{\"productId\":\"$hamb\",\"quantity\":1}]" '[{"method":"CASH","amount":25000}]'
  local cash_sale=$SALE
  wait_for "venta contado APROBADO" 720 sale_invoice_is "$cash_sale" APROBADO
  local cash_invoice; cash_invoice=$(jq -r .id <<< "$INVOICE")
  [ -n "$(jq -r '.cdc // empty' <<< "$INVOICE")" ] || die "factura aprobada sin CDC"
  ok "contado APROBADO, CDC $(jq -r .cdc <<< "$INVOICE"), Nº $(jq -r .invoiceNumber <<< "$INVOICE")"

  # Idempotencia: el mismo request del POS a Nexus devuelve la misma operación y SIFEN tiene un solo DE.
  local payload; payload=$(pos_sql "SELECT external_sync_payload FROM invoices WHERE id = '$cash_invoice'")
  m2m_token
  req POST "$NEXUS/api/v1/invoices" "$M2M" "$payload"; expect 200 "reenvío idéntico a Nexus"
  [ "$(sifen_documents "$cash_sale")" = 1 ] || die "SIFEN tiene $(sifen_documents "$cash_sale") DE para $cash_sale"
  ok "reenvío idéntico → 200 $(jq -r .status <<< "$BODY"), 1 DE en SIFEN"

  local kude; kude=$(mktemp); curl -sS -o "$kude" -H "Authorization: Bearer $PTOK" "$POS/printing/invoice/$cash_invoice/pdf/a4"
  [ "$(head -c 4 "$kude")" = "%PDF" ] || die "KuDE no es un PDF"
  ok "KuDE descargado desde el POS ($(wc -c < "$kude" | tr -d ' ') bytes)"

  pos GET "/customers?search=La%20Esquina&limit=5"; expect 200 "cliente a crédito"
  local customer; customer=$(jq -r '[(if type == "array" then . else .data end)[] | select(.creditEnabled)][0].id' <<< "$BODY")
  # Con "cobro diferido" (posDeferPayment, la hamburguesería) una venta pendiente no es crédito: se apaga
  # mientras se crea la venta a crédito y se restaura.
  pos GET /config/organization; expect 200 "ajustes POS"
  local deferred; deferred=$(jq -r '.posDeferPayment // false' <<< "$BODY")
  pos PUT /config/organization '{"posDeferPayment":false}'; expect 200 "cobro diferido off"
  pos_sale "[{\"productId\":\"$hamb\",\"quantity\":2}]" '[]' ",\"customerId\":\"$customer\",\"dueDate\":\"$(date -v+30d +%F)\""
  local credit_sale=$SALE
  pos PUT /config/organization "{\"posDeferPayment\":$deferred}"; expect 200 "restaurar cobro diferido"
  wait_for "venta a crédito APROBADO" 720 sale_invoice_is "$credit_sale" APROBADO
  [ "$(jq -r .paymentCondition <<< "$(pos_sql "SELECT external_sync_payload->'header' FROM invoices WHERE sale_id = '$credit_sale'")")" = CREDIT ] \
    || die "la factura de la venta a crédito no viajó como CREDIT"
  ok "crédito (cuota con vencimiento) APROBADO, CDC $(jq -r .cdc <<< "$INVOICE")"

  # NC sobre la factura con RUC: SIFEN no admite receptor innominado en una NC (regla 1331).
  pos POST "/credit-notes/$(jq -r .id <<< "$INVOICE")" '{"reason":"Devolución E2E","reasonCode":"2"}'; expect "200 201" "nota de crédito"
  local credit_note; credit_note=$(jq -r .id <<< "$BODY")
  wait_for "nota de crédito APROBADO" 720 credit_note_is "$credit_note" APROBADO
  ok "nota de crédito APROBADO, CDC $(jq -r .cdc <<< "$BODY")"

  pos_sale "[{\"productId\":\"$hamb\",\"quantity\":1}]" '[{"method":"CASH","amount":25000}]'
  local cancel_sale=$SALE
  wait_for "venta a anular APROBADO" 720 sale_invoice_is "$cancel_sale" APROBADO
  local cancel_invoice; cancel_invoice=$(jq -r .id <<< "$INVOICE")
  # El POS exige anular primero la venta (devolución operativa) y después el comprobante fiscal.
  pos POST "/sales/$cancel_sale/cancel" '{"reason":"Anulación E2E","restock":true}'; expect "200 201" "anular venta"
  pos GET "/invoices/$cancel_invoice"
  if [ "$(jq -r .status <<< "$BODY")" = ISSUED ]; then
    pos POST "/invoices/$cancel_invoice/cancel" '{"reason":"Anulación E2E"}'; expect "200 201" "anular factura"
  fi
  wait_for "factura CANCELADO" 720 sale_invoice_is "$cancel_sale" CANCELADO
  ok "factura anulada (CANCELADO en el POS)"

  # SIFEN caído durante la emisión: Nexus reintenta y al volver completa sin duplicar.
  docker stop "$SIFEN_BACKEND" >/dev/null
  pos_sale "[{\"productId\":\"$hamb\",\"quantity\":1}]" '[{"method":"CASH","amount":25000}]'
  local outage_sale=$SALE
  wait_for "reintentos en Nexus con SIFEN caído" 120 operation_retrying "$outage_sale"
  ok "SIFEN caído → operación $(jq -r .status <<< "$OP") con $(jq -r .attempts <<< "$OP") intentos"
  docker start "$SIFEN_BACKEND" >/dev/null
  wait_for "emisión completada tras volver SIFEN" 600 sale_invoice_is "$outage_sale" APROBADO
  [ "$(sifen_documents "$outage_sale")" = 1 ] || die "SIFEN tiene $(sifen_documents "$outage_sale") DE para $outage_sale"
  ok "SIFEN volvió → APROBADO, 1 DE en SIFEN"
}

credit_note_is() {
  pos GET "/credit-notes/$1"; [ "$STATUS" = 200 ] || return 1
  [ "$(jq -r .externalEstado <<< "$BODY")" = "$2" ]
}

operation_retrying() {
  nexus_operation "$1"
  [ -n "$OP" ] && [ "$(jq -r .attempts <<< "$OP")" -ge 2 ]
}

# ---------------------------------------------------------------- 3. catálogo
run_integration() {  # integración → espera la corrida terminada
  nexus POST "/api/admin/integrations/$1/run?tenantId=$TENANT"; expect "200 201 202" "correr integración"
  RUN_OP=$(jq -r .operationId <<< "$BODY")
  wait_for "corrida $RUN_OP terminada" 180 operation_status_is "$RUN_OP" SUCCEEDED
}

operation_status_is() {
  nexus GET "/api/admin/operations/$1?tenantId=$TENANT"; [ "$STATUS" = 200 ] || return 1
  OP=$BODY
  [ "$(jq -r .status <<< "$OP")" = "$2" ]
}

gifthub_product() {  # código POS → deja el producto de GiftHub en GH_PRODUCT
  gh GET /api/admin/products; expect 200 "productos GiftHub"
  GH_PRODUCT=$(jq -c --arg c "$1" '[.products[] | select(.integration.externalId? == $c)][0] // empty' <<< "$BODY")
  [ -n "$GH_PRODUCT" ]
}

phase_catalog() {
  step "3. Catálogo: POS → GiftHub"
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 500
  ensure_product E2E-ARCH "Combo a archivar E2E" 18000 20
  local archived_id=$PRODUCT
  run_integration "$INT_CATALOG"
  gifthub_product E2E-HAMB || die "E2E-HAMB no llegó a GiftHub"
  [ "$(jq -r .available <<< "$GH_PRODUCT")" = true ] || die "E2E-HAMB no disponible en GiftHub"
  gifthub_product E2E-ARCH && [ "$(jq -r .available <<< "$GH_PRODUCT")" = true ] || die "E2E-ARCH no disponible en GiftHub"
  ok "sync POS → GiftHub: E2E-HAMB y E2E-ARCH disponibles ($(jq -c .result <<< "$OP"))"

  pos PUT "/products/$archived_id" '{"active":false}'; expect 200 "desactivar E2E-ARCH en el POS"
  run_integration "$INT_CATALOG"
  gifthub_product E2E-ARCH || die "E2E-ARCH desapareció de GiftHub"
  [ "$(jq -r .available <<< "$GH_PRODUCT")" = false ] || die "E2E-ARCH sigue disponible en GiftHub"
  ok "desactivado en el POS → archivado en GiftHub (available=false; $(jq -c .result <<< "$OP"))"
}

# ---------------------------------------------------------------- 4. pedidos
gifthub_order() {  # cantidad → deja el id del pedido en GH_ORDER
  gh POST /api/admin/customers '{"phone":"0981555123","name":"Cliente Nexus E2E"}'
  [ "$STATUS" = 201 ] || [ "$STATUS" = 409 ] || die "cliente GiftHub → HTTP $STATUS"
  gh GET "/api/admin/customers?q=Cliente%20Nexus%20E2E"; expect 200 "buscar cliente GiftHub"
  local customer; customer=$(jq -r '.items[0].id' <<< "$BODY")
  gifthub_product E2E-HAMB || die "E2E-HAMB no está en GiftHub (correr la fase catalog)"
  gh POST /api/admin/orders "{\"customerId\":\"$customer\",\"fulfillmentType\":\"PICKUP\",\"items\":[{\"productId\":\"$(jq -r .id <<< "$GH_PRODUCT")\",\"quantity\":$1}]}"
  expect 201 "pedido en GiftHub"; GH_ORDER=$(jq -r .id <<< "$BODY")
}

gh_order_is() {  # pedido estado [pago]
  gh GET "/api/admin/orders/$1"; [ "$STATUS" = 200 ] || return 1
  GH_VIEW=$BODY
  [ "$(jq -r .orderStatus <<< "$GH_VIEW")" = "$2" ] && { [ -z "${3:-}" ] || [ "$(jq -r .paymentStatus <<< "$GH_VIEW")" = "$3" ]; }
}

pos_channel_sale() {  # pedido GiftHub → venta del POS (vía API de integración, como Nexus)
  POS_ORDER=$(curl -sS -H "X-Api-Key: $POS_E2E_API_KEY" "$POS/integration/orders/GIFTHUB/$1")
  [ -n "$(jq -r '.saleId // empty' <<< "$POS_ORDER" 2>/dev/null)" ]
}

pos_sale_status_is() {
  pos GET "/sales/$1"; [ "$STATUS" = 200 ] && [ "$(jq -r .status <<< "$BODY")" = "$2" ]
}

phase_order() {
  step "4. Pedidos: GiftHub → Nexus → POS"
  ensure_cash_session
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 500
  gifthub_order 1
  local order=$GH_ORDER
  wait_for "pedido CONFIRMED en GiftHub" 120 gh_order_is "$order" CONFIRMED
  pos_channel_sale "$order" || die "sin venta de canal en el POS"
  local sale; sale=$(jq -r .saleId <<< "$POS_ORDER")
  ok "pedido $order → venta POS $(jq -r .status <<< "$POS_ORDER"), GiftHub CONFIRMED con Nº externo $(jq -r .externalOrderNumber <<< "$GH_VIEW")"

  pos GET "/sales/$sale"; expect 200 "venta de canal"
  pos POST "/sales/$sale/payments" "{\"method\":\"CASH\",\"amount\":$(jq -r .total <<< "$BODY"),\"cashSessionId\":\"$CASH_SESSION\"}"
  expect "200 201" "cobrar venta de canal"
  wait_for "pedido pagado y entregado en GiftHub" 180 gh_order_is "$order" DELIVERED PAID
  ok "cobrado en el POS → GiftHub PAID/DELIVERED"

  # Sin stock: el POS de prueba admite stock negativo; se apaga mientras dura el caso y se restaura.
  pos GET /config/organization; expect 200 "ajustes POS"
  local negative; negative=$(jq -r '.allowNegativeStock // false' <<< "$BODY")
  pos PUT /config/organization '{"allowNegativeStock":false}'; expect 200 "stock negativo off"
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 0
  gifthub_order 1
  local no_stock=$GH_ORDER
  wait_for "pedido sin stock CANCELLED" 120 gh_order_is "$no_stock" CANCELLED
  pos PUT /config/organization "{\"allowNegativeStock\":$negative}"; expect 200 "restaurar stock negativo"
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 500
  nexus_operation "$no_stock"
  local reason; reason=$(gh_sql "SELECT new_data->>'note' FROM audit_logs WHERE new_data->>'orderId' = '$no_stock' AND new_data->>'status' = 'CANCELLED' LIMIT 1")
  [ -n "$reason" ] || die "GiftHub canceló sin motivo"
  ok "sin stock → GiftHub CANCELLED con motivo «$reason» (Nexus $(jq -r .status <<< "$OP"))"

  gifthub_order 1
  local cancelled=$GH_ORDER
  wait_for "pedido a cancelar CONFIRMED" 120 gh_order_is "$cancelled" CONFIRMED
  pos_channel_sale "$cancelled"; local cancelled_sale; cancelled_sale=$(jq -r .saleId <<< "$POS_ORDER")
  gh PATCH "/api/admin/orders/$cancelled/status" '{"status":"CANCELLED"}'; expect 200 "cancelar en GiftHub"
  wait_for "venta anulada en el POS" 120 pos_sale_status_is "$cancelled_sale" CANCELLED
  ok "cancelado en GiftHub → venta $cancelled_sale anulada en el POS"
}

# ---------------------------------------------------------------- 5. operación
integration_state() {
  nexus POST "/api/admin/integrations/$1/state?tenantId=$TENANT" "{\"state\":\"$2\",\"reason\":\"e2e: $3\"}"
  expect 200 "integración → $2"
}

nexus_has_operation_for() { nexus_operation "$1"; [ -n "$OP" ]; }

phase_ops() {
  step "5. Operación: pausa, DEAD y replay"
  ensure_cash_session
  ensure_product E2E-HAMB "Hamburguesa Nexus E2E" 25000 500
  integration_state "$INT_ORDER" PAUSED "pausa de prueba"
  gifthub_order 1
  local paused=$GH_ORDER
  wait_for "webhook recibido con ORDER en pausa" 60 nexus_has_operation_for "$paused"
  sleep 20
  nexus_operation "$paused"
  gh_order_is "$paused" PENDING || die "el pedido avanzó con la integración en pausa (GiftHub $(jq -r .orderStatus <<< "$GH_VIEW"))"
  ok "ORDER en pausa: operación $(jq -r .status <<< "$OP") y pedido PENDING en GiftHub tras 20 s"
  integration_state "$INT_ORDER" ENABLED "reanudar"
  wait_for "pedido procesado al reanudar" 120 gh_order_is "$paused" CONFIRMED
  ok "reanudada → pedido CONFIRMED"

  # DEAD: la API key del POS rechazada (401) cierra la operación sin reintentos; replay tras corregirla.
  upsert_connection POS "POS Hamburguesería" "$POS_FROM_DOCKER" '{"apiKey":"pv_live_invalida_e2e"}' >/dev/null
  gifthub_order 1
  local dead=$GH_ORDER
  wait_for "operación DEAD" 120 operation_dead "$dead"
  local dead_op; dead_op=$(jq -r .id <<< "$OP")
  ok "credencial del POS inválida → DEAD ($(jq -r '.deadReason // .lastError.code' <<< "$OP"))"
  upsert_connection POS "POS Hamburguesería" "$POS_FROM_DOCKER" "{\"apiKey\":\"$POS_E2E_API_KEY\"}" >/dev/null
  if [ "${E2E_LEAVE_DEAD:-}" = 1 ]; then
    ok "operación DEAD $dead_op queda para el replay desde la consola"
    return
  fi
  nexus POST "/api/admin/operations/$dead_op/replay?tenantId=$TENANT"; expect "200 202" "replay"
  wait_for "pedido CONFIRMED tras el replay" 120 gh_order_is "$dead" CONFIRMED
  ok "replay → pedido CONFIRMED"
}

operation_dead() { nexus_operation "$1"; [ -n "$OP" ] && [ "$(jq -r .status <<< "$OP")" = DEAD ]; }

setup
for phase in $PHASES; do
  [ "$phase" = setup ] || "phase_$phase"
done
printf '\nE2E local OK\n'
