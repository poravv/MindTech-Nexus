package com.mindtechpy.nexus.domain.fiscal;

/**
 * Canonical status of a fiscal document (spec §8.1). {@code PENDING}: not yet accepted by SIFEN;
 * {@code SUBMITTED}: SIFEN has it but has not decided; {@code FAILED}: SIFEN refused the request;
 * {@code DEAD}: needs a human in the console.
 */
public enum InvoiceStatus {
    PENDING, SUBMITTED, APPROVED, APPROVED_WITH_NOTES, REJECTED, CANCELLED, FAILED, DEAD
}
