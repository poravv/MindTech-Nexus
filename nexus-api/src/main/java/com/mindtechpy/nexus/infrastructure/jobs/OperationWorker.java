package com.mindtechpy.nexus.infrastructure.jobs;

import com.mindtechpy.nexus.application.port.out.JobQueuePort;
import com.mindtechpy.nexus.application.saga.SagaRunner;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.infrastructure.web.CorrelationIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Polls the queue every 500 ms and runs each leased operation on its own virtual thread, never more than
 * {@code batchSize} at once. Graceful shutdown: stops claiming and waits up to {@code shutdownTimeout}
 * for running steps; whatever is still running keeps its lease and is rescued by {@link LeaseRescueJob}.
 */
public class OperationWorker {

    private static final Logger log = LoggerFactory.getLogger(OperationWorker.class);

    private final JobQueuePort queue;
    private final SagaRunner runner;
    private final int batchSize;
    private final Duration shutdownTimeout;
    private final String workerId = hostname() + ":" + ProcessHandle.current().pid();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger inFlight = new AtomicInteger();
    private boolean stopping;

    public OperationWorker(JobQueuePort queue, SagaRunner runner, int batchSize, Duration shutdownTimeout) {
        this.queue = queue;
        this.runner = runner;
        this.batchSize = batchSize;
        this.shutdownTimeout = shutdownTimeout;
    }

    @Scheduled(fixedDelay = 500)
    public synchronized void poll() {
        int free = batchSize - inFlight.get();
        if (stopping || free <= 0) {
            return;
        }
        for (Operation operation : queue.claim(workerId, free)) {
            inFlight.incrementAndGet();
            executor.execute(() -> runClaimed(operation));
        }
    }

    private void runClaimed(Operation operation) {
        MDC.put("operationId", operation.id().toString());
        MDC.put("tenantId", operation.tenantId().toString());
        if (operation.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, operation.correlationId());
        }
        try {
            runner.run(operation);
        } catch (RuntimeException e) {
            log.error("{} op={} error inesperado; el lease se rescatará", operation.flow().logPrefix(), operation.id(), e);
        } finally {
            inFlight.decrementAndGet();
            MDC.clear();
        }
    }

    @PreDestroy
    public synchronized void stop() throws InterruptedException {
        stopping = true;
        executor.shutdown();
        if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("Apagado con {} pasos en curso: sus leases se rescatarán", inFlight.get());
        }
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
