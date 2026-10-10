package com.mindtechpy.nexus.application.saga;

/**
 * Thrown by {@link Saga#compensate} when the remote effect must not be undone automatically (e.g. a paid
 * GiftHub order cannot be cancelled without a refund). The operation ends DEAD with reason
 * {@value #REASON} so a human reconciles both systems; the message, shown as the error detail, says what to check.
 */
public class ManualReconciliationRequired extends RuntimeException {

    public static final String REASON = "manual-reconciliation";

    public ManualReconciliationRequired(String message) {
        super(message);
    }
}
