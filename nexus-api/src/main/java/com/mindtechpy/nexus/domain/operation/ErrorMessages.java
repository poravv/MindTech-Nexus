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
            Map.entry("pos-bad-request", "El POS rechazó la solicitud"),
            Map.entry("pos-unprocessable", "El POS no pudo procesar la solicitud"),
            Map.entry("pos-not-found", "El POS no encontró el recurso pedido"),
            Map.entry("pos-conflict", "El POS tiene un conflicto con la solicitud"),
            Map.entry("pos-order-not-pending", "El pedido del POS ya no está pendiente"),
            Map.entry("pos-invalid-response", "El POS respondió con un formato inesperado"),
            Map.entry("pos-payment-not-applied", "El POS no registró el cobro"),
            Map.entry("gifthub-order-not-found", "GiftHub no encontró el pedido"),
            Map.entry("gifthub-order-cancelled", "El pedido está cancelado en GiftHub"),
            Map.entry("gifthub-order-not-cancellable", "El pedido de GiftHub ya no se puede cancelar"),
            Map.entry("gifthub-unknown-status", "GiftHub devolvió un estado desconocido"),
            Map.entry("gifthub-change-not-applied", "GiftHub no aplicó el cambio de estado"),
            Map.entry("IDEMPOTENCY_CONFLICT", "El sistema remoto ya recibió esta solicitud con otros datos"),
            Map.entry("EMISION_EN_CURSO", "El sistema remoto sigue procesando la solicitud"),
            Map.entry("BUSINESS_RULE", "El sistema remoto rechazó la solicitud por una regla de negocio"),
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
