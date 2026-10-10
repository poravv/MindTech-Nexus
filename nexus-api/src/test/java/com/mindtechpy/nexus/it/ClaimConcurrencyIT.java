package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimConcurrencyIT extends AbstractOperationIT {

    @Test
    void should_process_each_operation_exactly_once_when_two_workers_claim_concurrently() throws Exception {
        UUID integration = createIntegration(fastPolicy());
        List<UUID> ids = IntStream.range(0, 100).mapToObj(i -> start(integration, "op-" + i).id()).toList();
        Set<UUID> claimedByA = ConcurrentHashMap.newKeySet();
        Set<UUID> claimedByB = ConcurrentHashMap.newKeySet();
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> a = workers.submit(() -> work("worker-a", claimedByA, go));
            Future<?> b = workers.submit(() -> work("worker-b", claimedByB, go));
            go.countDown();
            a.get();
            b.get();
        }

        assertThat(ids).allSatisfy(id -> {
            assertThat(saga.callsFor(id)).as("ejecuciones de %s", id).isEqualTo(1);
            assertThat(reload(id).status()).isEqualTo(OperationStatus.SUCCEEDED);
        });
        assertThat(claimedByA).doesNotContainAnyElementsOf(claimedByB);
        assertThat(claimedByA).isNotEmpty();
        assertThat(claimedByB).isNotEmpty();
    }

    private void work(String worker, Set<UUID> claimed, CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        List<Operation> batch;
        while (!(batch = queue.claim(worker, 5)).isEmpty()) {
            for (Operation operation : batch) {
                claimed.add(operation.id());
                runner.run(operation);
            }
        }
    }
}
