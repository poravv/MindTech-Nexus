package com.mindtechpy.nexus.domain.fiscal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A document as SIFEN returns it. {@code documentNumber} is {@code EEE-PPP-NNNNNNN} when SIFEN reports
 * establishment and point. It is kept in the operation result, so its keys are the fields of the
 * canonical invoice view.
 */
public record SifenDocument(String cdc, String estado, String documentNumber, String providerCode,
                            String providerMessage, Long total, String updatedAt) {

    /** Effect validation (spec §9): only a CDC plus a known state proves SIFEN registered the document. */
    public boolean isValid() {
        return cdc != null && !cdc.isBlank() && ProviderStatus.parse(estado).isPresent();
    }

    public ProviderStatus status() {
        return ProviderStatus.parse(estado).orElseThrow();
    }

    public Map<String, Object> toResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cdc", cdc);
        result.put("providerStatus", estado);
        result.put("documentNumber", documentNumber);
        result.put("providerCode", providerCode);
        result.put("providerMessage", providerMessage);
        result.put("total", total);
        result.put("providerUpdatedAt", updatedAt);
        return result;
    }
}
