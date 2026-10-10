package com.mindtechpy.nexus.domain.integration;

/** Business flows of the MVP (spec §1). {@code logPrefix} is the stable log prefix of each flow. */
public enum Flow {
    FISCAL, ORDER, CATALOG;

    public String logPrefix() {
        return "[NEXUS-" + name() + "]";
    }
}
