package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.infrastructure.jobs.PayloadRetentionJob;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Retention clears old payloads of succeeded operations only: FAILED and DEAD must stay replayable. */
class PayloadRetentionIT extends AbstractOperationIT {

    @Test
    void should_keep_payload_of_failed_and_dead_operations_when_clearing_old_payloads() {
        UUID integration = createIntegration(fastPolicy());
        Operation succeeded = finishedLongAgo(start(integration, "ret-ok-" + UUID.randomUUID()), "SUCCEEDED");
        Operation failed = finishedLongAgo(start(integration, "ret-failed-" + UUID.randomUUID()), "FAILED");
        Operation dead = finishedLongAgo(start(integration, "ret-dead-" + UUID.randomUUID()), "DEAD");

        new PayloadRetentionJob(jdbc).purge();

        assertThat(reload(succeeded.id()).payload()).isNull();
        assertThat(reload(failed.id()).payload()).isNotNull();
        assertThat(reload(dead.id()).payload()).isNotNull();
    }

    private Operation finishedLongAgo(Operation op, String status) {
        jdbc.sql("UPDATE operations SET status = :status, finished_at = :at WHERE id = :id")
                .param("status", status).param("at", Instant.now().minus(31, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC))
                .param("id", op.id()).update();
        return op;
    }
}
