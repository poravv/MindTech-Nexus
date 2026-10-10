package com.mindtechpy.nexus.infrastructure.metrics;

import com.mindtechpy.nexus.application.port.out.ClockPort;
import com.mindtechpy.nexus.application.port.out.OperationMetrics;
import com.mindtechpy.nexus.domain.integration.Flow;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Prometheus metrics of spec §13 ({@code nexus_operations_total}, {@code nexus_operation_duration_seconds},
 * {@code nexus_step_duration_seconds}, {@code nexus_queue_depth}, {@code nexus_dead_letters_total},
 * {@code nexus_rate_limited_total}). No tenant label. {@code nexus_connector_latency_seconds} belongs to the
 * connectors. Queue depth, queue lag ({@code nexus_queue_lag_seconds}: how long the oldest due PENDING/RETRY operation
 * has waited for a worker) and {@code nexus_operation_oldest_unfinished_age_seconds{flow}} (age of the oldest operation
 * not finished, the "stuck" signal) are read from the table on each scrape, for the alerts of
 * {@code k8s/prometheusrule.yaml}. Ages are measured with {@link ClockPort}, the clock that wrote the timestamps,
 * never with Postgres {@code now()}.
 */
@Component
class MicrometerOperationMetrics implements OperationMetrics {

    private static final List<OperationStatus> QUEUED = List.of(OperationStatus.PENDING, OperationStatus.RETRY,
            OperationStatus.RUNNING, OperationStatus.DEAD);

    private final MeterRegistry registry;

    MicrometerOperationMetrics(MeterRegistry registry, JdbcClient jdbc, ClockPort clock) {
        this.registry = registry;
        for (OperationStatus status : QUEUED) {
            Gauge.builder("nexus.queue.depth", () -> jdbc.sql("SELECT count(*) FROM operations WHERE status = :status")
                            .param("status", status.name()).query(Long.class).single())
                    .tag("status", status.name())
                    .description("Operaciones por estado")
                    .register(registry);
        }
        Gauge.builder("nexus.queue.lag", () -> jdbc.sql("""
                        SELECT COALESCE(EXTRACT(EPOCH FROM :now - min(next_run_at)), 0) FROM operations
                        WHERE status IN ('PENDING', 'RETRY') AND next_run_at <= :now""")
                        .param("now", clock.now().atOffset(ZoneOffset.UTC)).query(Double.class).single())
                .baseUnit("seconds")
                .description("Espera de la operación vencida más antigua sin worker")
                .register(registry);
        for (Flow flow : Flow.values()) {
            Gauge.builder("nexus.operation.oldest.unfinished.age", () -> jdbc.sql("""
                            SELECT COALESCE(EXTRACT(EPOCH FROM :now - min(created_at)), 0) FROM operations
                            WHERE status IN ('PENDING', 'RETRY', 'RUNNING') AND flow = :flow""")
                            .param("now", clock.now().atOffset(ZoneOffset.UTC)).param("flow", flow.name())
                            .query(Double.class).single())
                    .tag("flow", flow.name())
                    .baseUnit("seconds")
                    .description("Antigüedad de la operación sin terminar más vieja")
                    .register(registry);
        }
    }

    @Override
    public void operationCreated(OperationKind kind) {
        countOperation(kind, OperationStatus.PENDING);
    }

    @Override
    public void operationFinished(OperationKind kind, OperationStatus status, Duration sinceCreation) {
        countOperation(kind, status);
        Timer.builder("nexus.operation.duration").tag("flow", kind.flow().name()).tag("kind", kind.name())
                .register(registry).record(sinceCreation);
        if (status == OperationStatus.DEAD) {
            Counter.builder("nexus.dead.letters").tag("flow", kind.flow().name()).register(registry).increment();
        }
    }

    @Override
    public void stepFinished(Flow flow, String step, Duration duration) {
        Timer.builder("nexus.step.duration").tag("flow", flow.name()).tag("step", step).register(registry).record(duration);
    }

    @Override
    public void rateLimited(Flow flow) {
        Counter.builder("nexus.rate.limited").tag("flow", flow.name()).register(registry).increment();
    }

    private void countOperation(OperationKind kind, OperationStatus status) {
        Counter.builder("nexus.operations").tag("flow", kind.flow().name()).tag("kind", kind.name())
                .tag("status", status.name()).register(registry).increment();
    }
}
