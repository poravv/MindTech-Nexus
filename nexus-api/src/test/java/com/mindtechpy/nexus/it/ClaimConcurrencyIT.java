package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operations left by other tests may be claimed here too, and a multi-step one is legitimately claimed
 * again for each step (possibly by the other worker), so the exactly-once checks filter by this test's ids.
 */
class ClaimConcurrencyIT extends AbstractOperationIT {

    @Test
    void should_process_each_operation_exactly_once_when_two_workers_claim_concurrently() throws Exception {
        UUID integration = createIntegration(fastPolicy());
        List<UUID> ids = IntStream.range(0, 100).mapToObj(i -> start(integration, "op-" + i).id()).toList();
        Queue<UUID> claimedByA = new ConcurrentLinkedQueue<>();
        Queue<UUID> claimedByB = new ConcurrentLinkedQueue<>();
        CyclicBarrier bothHoldLeases = new CyclicBarrier(2);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> a = workers.submit(() -> work("worker-a", claimedByA, bothHoldLeases));
            Future<?> b = workers.submit(() -> work("worker-b", claimedByB, bothHoldLeases));
            a.get();
            b.get();
        }

        assertThat(Stream.concat(claimedByA.stream(), claimedByB.stream()).filter(ids::contains))
                .as("cada operación reclamada una sola vez entre ambos workers")
                .containsExactlyInAnyOrderElementsOf(ids);
        assertThat(ids).allSatisfy(id -> {
            assertThat(saga.callsFor(id)).as("ejecuciones de %s", id).isEqualTo(1);
            assertThat(reload(id).status()).isEqualTo(OperationStatus.SUCCEEDED);
        });
        assertThat(claimedByA).isNotEmpty();
        assertThat(claimedByB).isNotEmpty();
    }

    /** The barrier makes the first claims overlap: each worker claims while the other still holds its leases. */
    private Void work(String worker, Queue<UUID> claimed, CyclicBarrier bothHoldLeases) throws Exception {
        List<Operation> batch = queue.claim(worker, 5);
        bothHoldLeases.await(30, TimeUnit.SECONDS);
        while (!batch.isEmpty()) {
            for (Operation operation : batch) {
                claimed.add(operation.id());
                runner.run(operation);
            }
            batch = queue.claim(worker, 5);
        }
        return null;
    }
}
