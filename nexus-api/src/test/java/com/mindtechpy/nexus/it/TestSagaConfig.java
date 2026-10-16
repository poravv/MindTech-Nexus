package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.saga.Saga;
import com.mindtechpy.nexus.application.saga.StepContext;
import com.mindtechpy.nexus.application.saga.StepResult;
import com.mindtechpy.nexus.application.saga.catalog.CatalogSyncSaga;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.identity.Actor;
import com.mindtechpy.nexus.domain.identity.Scope;
import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.infrastructure.security.TenantContext;
import com.mindtechpy.nexus.infrastructure.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Test-only plumbing to exercise the engine without real connectors: a saga whose behaviour each test
 * scripts, and a runtime endpoint that starts operations for it like S3/S5 controllers will.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSagaConfig {

    /**
     * The engine tests script {@code CATALOG_SYNC}, the kind of the real {@link CatalogSyncSaga}: the real saga
     * bean is wrapped (one saga per kind) and still runs every integration that has a source connection,
     * which engine tests never set. Real catalog runs then start at step {@code FIRST}; the catalog saga does not
     * depend on the step name.
     */
    @Bean
    static BeanPostProcessor scriptedCatalogSaga() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof CatalogSyncSaga real ? new ScriptedSaga(real) : bean;
            }
        };
    }

    /** Runs engine-test {@code CATALOG_SYNC} operations with the behaviour set by the current test; records every call. */
    public static class ScriptedSaga implements Saga {

        public record Call(UUID operationId, String step, boolean verification) {
        }

        public record Compensation(UUID operationId, String step, ConnectorError cause) {
        }

        public final Queue<Call> calls = new ConcurrentLinkedQueue<>();
        public final Queue<Compensation> compensations = new ConcurrentLinkedQueue<>();
        private final Saga real;
        private volatile Function<StepContext, StepResult> behavior;

        ScriptedSaga(Saga real) {
            this.real = real;
            reset();
        }

        public void reset() {
            calls.clear();
            compensations.clear();
            behavior = context -> new StepResult.Completed(null, Map.of("done", true));
        }

        public void behave(Function<StepContext, StepResult> behavior) {
            this.behavior = behavior;
        }

        public long callsFor(UUID operationId) {
            return calls.stream().filter(call -> call.operationId().equals(operationId)).count();
        }

        @Override
        public OperationKind kind() {
            return OperationKind.CATALOG_SYNC;
        }

        @Override
        public String firstStep() {
            return "FIRST";
        }

        @Override
        public StepResult execute(StepContext context) {
            if (context.source() != null) {
                return real.execute(context);
            }
            calls.add(new Call(context.operation().id(), context.step(), context.operation().needsVerification()));
            return behavior.apply(context);
        }

        @Override
        public boolean compensate(StepContext context, ConnectorError cause) {
            if (context.source() != null) {
                return real.compensate(context, cause);
            }
            compensations.add(new Compensation(context.operation().id(), context.step(), cause));
            return false;
        }
    }

    /** Registered as a member class of this configuration. */
    @RestController
    public static class TestOperationController {

        public record StartRequest(UUID integrationId, String key, Map<String, Object> payload) {
        }

        private final StartOperation startOperation;

        TestOperationController(StartOperation startOperation) {
            this.startOperation = startOperation;
        }

        @PostMapping("/api/v1/test-operations")
        ResponseEntity<Map<String, Object>> start(@RequestBody StartRequest request) {
            Actor client = TenantContext.currentClient(Scope.OPERATIONS_READ);
            StartOperation.Started started = startOperation.execute(new StartOperation.Command(client.tenantId(),
                    request.integrationId(), OperationKind.CATALOG_SYNC, request.key(), request.payload(),
                    MDC.get(CorrelationIdFilter.MDC_KEY)));
            return ResponseEntity.status(started.created() ? 202 : 200).body(Map.of(
                    "operationId", started.operation().id().toString(), "status", started.operation().status().name()));
        }
    }
}
