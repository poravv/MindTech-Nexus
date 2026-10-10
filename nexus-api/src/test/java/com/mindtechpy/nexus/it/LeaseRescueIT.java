package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeaseRescueIT extends AbstractOperationIT {

    @Test
    void should_return_operation_to_retry_and_finish_it_when_worker_dies_holding_the_lease() {
        UUID integration = createIntegration(fastPolicy());
        Operation started = start(integration, "rescue-1");
        claimOnly(started.id(), "worker-that-dies");

        queue.rescueExpiredLeases();
        assertThat(reload(started.id()).status()).as("lease vigente: no se rescata").isEqualTo(OperationStatus.RUNNING);

        expireLease(started.id());
        assertThat(queue.rescueExpiredLeases()).isGreaterThanOrEqualTo(1);
        Operation rescued = reload(started.id());
        assertThat(rescued.status()).isEqualTo(OperationStatus.RETRY);
        assertThat(rescued.lockedBy()).isNull();

        drain();

        Operation finished = reload(started.id());
        assertThat(finished.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(finished.attempts()).isEqualTo(2);
        assertThat(saga.callsFor(started.id())).isEqualTo(1);
    }

    @Test
    void should_discard_late_result_when_lease_was_rescued_and_claimed_by_another_worker() {
        UUID integration = createIntegration(fastPolicy());
        Operation started = start(integration, "rescue-2");
        Operation stale = claimOnly(started.id(), "slow-worker");
        expireLease(started.id());
        queue.rescueExpiredLeases();
        Operation current = claimOnly(started.id(), "healthy-worker");

        boolean lateWriteAccepted = operations.update(stale, stale.succeeded(Map.of("from", "slow"), stale.createdAt()));

        assertThat(stale.lockedBy()).isEqualTo("slow-worker");
        assertThat(lateWriteAccepted).isFalse();
        runner.run(current);
        Operation finished = reload(started.id());
        assertThat(finished.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(finished.result()).containsEntry("done", true).doesNotContainKey("from");
    }

    private void expireLease(UUID id) {
        jdbc.sql("UPDATE operations SET locked_until = now() - interval '1 second' WHERE id = :id").param("id", id).update();
    }
}
