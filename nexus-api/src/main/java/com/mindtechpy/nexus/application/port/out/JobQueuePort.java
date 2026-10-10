package com.mindtechpy.nexus.application.port.out;

import com.mindtechpy.nexus.domain.operation.Operation;

import java.util.List;

/**
 * Durable work queue (ADR-002). v1 is the {@code operations} table with {@code FOR UPDATE SKIP LOCKED};
 * a broker would be another implementation.
 */
public interface JobQueuePort {

    /**
     * Atomically leases up to {@code limit} due operations (PENDING/RETRY, {@code next_run_at <= now})
     * whose integration dispatches, marks them RUNNING and counts one attempt. Two workers never get the
     * same operation.
     */
    List<Operation> claim(String workerId, int limit);

    /** Returns RUNNING operations with an expired lease (crashed worker) to RETRY; answers how many. */
    int rescueExpiredLeases();
}
