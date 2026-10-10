package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.infrastructure.jobs.OperationWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The real scheduled worker in its own context ({@code nexus.worker.enabled=true}); closed after the class. */
@SpringBootTest(properties = {"nexus.worker.enabled=true", "nexus.queue.max-unfinished-per-tenant=150"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerIT extends AbstractOperationIT {

    @Autowired
    private OperationWorker worker;

    @Test
    void should_process_operations_in_background_when_worker_is_enabled() throws InterruptedException {
        UUID integration = createIntegration(fastPolicy());

        Operation op = start(integration, "background-1");

        await(() -> reload(op.id()).status() == OperationStatus.SUCCEEDED);
    }

    @Test
    @DirtiesContext
    void should_let_running_steps_finish_before_stopping_when_shutting_down() throws InterruptedException {
        UUID integration = createIntegration(fastPolicy());
        saga.behave(context -> {
            sleep(Duration.ofSeconds(1));
            return new StepResult.Completed(null, null);
        });
        Operation op = start(integration, "graceful-1");
        await(() -> saga.callsFor(op.id()) == 1);

        worker.stop();

        assertThat(reload(op.id()).status()).isEqualTo(OperationStatus.SUCCEEDED);
        Operation afterStop = start(integration, "graceful-2");
        Thread.sleep(1500);
        assertThat(reload(afterStop.id()).status()).as("no reclama tras el apagado").isEqualTo(OperationStatus.PENDING);
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.get()) {
            assertThat(System.nanoTime()).as("tiempo de espera agotado").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
