package com.mindtechpy.nexus.domain.operation;

import java.util.Map;

/**
 * Fixed Spanish description per known error code. Remote free text is never stored because it can carry RUC,
 * phone numbers or names; unknown codes get a generic message that still names the code.
 */
public final class ErrorMessages {

    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("invalid-ruc", "El POS rechazó el RUC del cliente"),
            Map.entry("ruc-in-use", "El RUC ya pertenece a otro cliente del POS"),
            Map.entry("cash-session-closed", "El POS no tiene la caja abierta"),
            Map.entry("amount-mismatch", "El monto cobrado no coincide con el total del pedido en el POS"),
            Map.entry("partial-payment-exists", "El pedido ya tiene pagos en el POS: requiere conciliación manual"),
            Map.entry("not-pending", "El pedido del POS ya no está pendiente de cobro"),
            Map.entry("sale-cancelled", "El pedido está anulado en el POS"),
            Map.entry("not-completed", "El POS registró el cobro pero la venta no quedó completada"),
            Map.entry("insufficient-stock", "El POS no tiene stock suficiente para el pedido"),
            Map.entry("unknown-product", "El pedido tiene productos que el POS no conoce o no vende"),
            Map.entry("zero-total", "El POS no acepta un pedido en Gs. 0"),
            Map.entry("pos-bad-request", "El POS rechazó la solicitud"),
            Map.entry("pos-unprocessable", "El POS no pudo procesar la solicitud"),
            Map.entry("pos-not-found", "El POS no encontró el recurso pedido"),
            Map.entry("pos-conflict", "El POS tiene un conflicto con la solicitud"),
            Map.entry("pos-order-not-pending", "El pedido del POS ya no está pendiente"),
            Map.entry("invoiced", "La venta del POS está facturada: requiere nota de crédito"),
            Map.entry("not-cancellable", "El POS no permite anular la venta en su estado actual"),
            Map.entry("gifthub-payment-not-refunded", "El pedido de GiftHub está cancelado pero el pago no se devolvió"),
            Map.entry("pos-invalid-response", "El POS respondió con un formato inesperado"),
            Map.entry("pos-payment-not-applied", "El POS no registró el cobro"),
            Map.entry("gifthub-order-not-found", "GiftHub no encontró el pedido"),
            Map.entry("gifthub-order-cancelled", "El pedido está cancelado en GiftHub"),
            Map.entry("gifthub-order-not-cancellable", "El pedido de GiftHub ya no se puede cancelar"),
            Map.entry("gifthub-unknown-status", "GiftHub devolvió un estado desconocido"),
            Map.entry("gifthub-change-not-applied", "GiftHub no aplicó el cambio de estado"),
            Map.entry("NOT_FOUND", "El sistema remoto no encontró el recurso pedido"),
            Map.entry("VALIDATION", "GiftHub rechazó los datos enviados"),
            Map.entry("TRANSITION_NOT_ALLOWED", "GiftHub no permite ese cambio de estado del pedido"),
            Map.entry("INVALID_CREDENTIAL", "GiftHub rechazó la API key"),
            Map.entry("IP_NOT_ALLOWED", "GiftHub no permite llamadas desde esta IP"),
            Map.entry("INTEGRATIONS_DISABLED", "El negocio no tiene las integraciones habilitadas en GiftHub"),
            Map.entry("IDEMPOTENCY_CONFLICT", "El sistema remoto ya recibió esta solicitud con otros datos"),
            Map.entry("EMISION_EN_CURSO", "El sistema remoto sigue procesando la solicitud"),
            Map.entry("BUSINESS_RULE", "El sistema remoto rechazó la solicitud por una regla de negocio"),
            Map.entry("VALIDATION_ERROR", "SIFEN rechazó los datos del documento"),
            Map.entry("INVALID_ARGUMENT", "SIFEN rechazó un dato del documento"),
            Map.entry("MALFORMED_REQUEST", "SIFEN no pudo leer la solicitud"),
            Map.entry("MISSING_PARAMETER", "A la solicitud a SIFEN le falta un dato obligatorio"),
            Map.entry("CONFLICT", "SIFEN tiene un conflicto con el estado del documento"),
            Map.entry("PLAN_LIMIT_EXCEEDED", "El plan de SIFEN alcanzó su límite"),
            Map.entry("PLAN_LIMIT_SUCURSALES", "El plan de SIFEN no admite más sucursales"),
            Map.entry("WORKSPACE_SUSPENDED", "El workspace de SIFEN está suspendido"),
            Map.entry("INVALID_CREDENTIALS", "SIFEN rechazó las credenciales"),
            Map.entry("ACCESS_DENIED", "SIFEN no permite esta operación con las credenciales actuales"),
            Map.entry("RATE_LIMIT_EXCEEDED", "SIFEN pidió bajar el ritmo de solicitudes"),
            Map.entry("SERVICE_ERROR", "SIFEN no está disponible por el momento"),
            Map.entry("INTERNAL_ERROR", "SIFEN tuvo un error interno"),
            Map.entry("credentials-missing", "La conexión no tiene todas las credenciales"),
            Map.entry("integration-misconfigured", "La integración no está configurada"),
            Map.entry("customer-missing", "El pedido no trae datos del cliente"),
            Map.entry("invoice-not-found", "No se encontró la factura"),
            Map.entry("sale-pending", "La venta del POS sigue pendiente"),
            Map.entry("connect-failed", "No se pudo conectar con el sistema remoto"),
            Map.entry("no-response", "El sistema remoto no respondió tras enviar la solicitud"),
            Map.entry("read-timeout", "El sistema remoto no respondió a tiempo"),
            Map.entry("io-error", "Error de red con el sistema remoto"),
            Map.entry("invalid-response", "El sistema remoto respondió con un formato inesperado"),
            Map.entry("lease-expired", "El worker se detuvo durante el paso"),
            Map.entry("unexpected-error", "Error inesperado durante el paso"));

    private ErrorMessages() {
    }

    /** The fixed message for {@code code}, or "Error del sistema remoto (code)" when the code is unknown. */
    public static String describe(String code) {
        return BY_CODE.getOrDefault(code, "Error del sistema remoto (" + code + ")");
    }
}
