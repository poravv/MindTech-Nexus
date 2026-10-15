package com.mindtechpy.nexus.domain.operation;

import com.mindtechpy.nexus.domain.integration.Flow;

/** Kinds of durable operations (spec §6) and the flow each belongs to. */
public enum OperationKind {
    ISSUE_INVOICE(Flow.FISCAL),
    CANCEL_INVOICE(Flow.FISCAL),
    CREATE_ORDER(Flow.ORDER),
    ORDER_STATUS(Flow.ORDER),
    ORDER_PAID(Flow.ORDER),
    CATALOG_SYNC(Flow.CATALOG);

    private final Flow flow;

    OperationKind(Flow flow) {
        this.flow = flow;
    }

    public Flow flow() {
        return flow;
    }
}
