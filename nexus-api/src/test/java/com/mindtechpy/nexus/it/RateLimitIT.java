package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitIT extends AbstractOperationIT {

    @Autowired
    private MeterRegistry meters;

    @Test
    void should_run_burst_and_defer_the_rest_one_second_without_spending_attempts_when_tokens_run_out() {
        Map<String, Object> policy = fastPolicy();
        policy.put("ratePerSecond", 0.01);
        policy.put("burst", 5);
        UUID integration = createIntegration(policy);
        List<UUID> ids = IntStream.range(0, 8).mapToObj(i -> start(integration, "burst-" + i).id()).toList();
        double limitedBefore = rateLimited();

        Instant before = Instant.now();
        drain();

        List<Operation> after = ids.stream().map(this::reload).toList();
        assertThat(after).filteredOn(op -> op.status() == OperationStatus.SUCCEEDED).hasSize(5);
        assertThat(after).filteredOn(op -> op.status() == OperationStatus.RETRY).hasSize(3).allSatisfy(op -> {
            assertThat(op.attempts()).isZero();
            assertThat(op.nextRunAt()).isAfter(before.plus(Duration.ofMillis(900)));
        });
        assertThat(rateLimited()).isGreaterThanOrEqualTo(limitedBefore + 3);
    }

    @Test
    void should_never_run_more_steps_in_parallel_than_max_in_flight() throws Exception {
        Map<String, Object> policy = fastPolicy();
        policy.put("maxInFlight", 2);
        UUID integration = createIntegration(policy);
        Set<UUID> ids = IntStream.range(0, 4).mapToObj(i -> start(integration, "parallel-" + i).id()).collect(Collectors.toSet());
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        saga.behave(context -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return new StepResult.Completed(null, null);
        });
        List<Operation> ours = claimOnly(ids, "it-worker");

        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            ours.forEach(op -> pool.execute(() -> runner.run(op)));
            awaitDeferred(ids, 2);
            release.countDown();
        }

        assertThat(maxRunning.get()).isEqualTo(2);
        List<Operation> after = ids.stream().map(this::reload).toList();
        assertThat(after).filteredOn(op -> op.status() == OperationStatus.SUCCEEDED).hasSize(2);
        assertThat(after).filteredOn(op -> op.status() == OperationStatus.RETRY).hasSize(2)
                .allSatisfy(op -> assertThat(op.attempts()).isZero());
    }

    private void awaitDeferred(Set<UUID> ids, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ids.stream().map(this::reload).filter(op -> op.status() == OperationStatus.RETRY).count() < expected) {
            assertThat(System.nanoTime()).as("esperando operaciones diferidas").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private double rateLimited() {
        Counter counter = meters.find("nexus.rate.limited").tag("flow", "CATALOG").counter();
        return counter == null ? 0 : counter.count();
    }
}
