package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyIT extends AbstractOperationIT {

    @Test
    void should_persist_before_202_and_return_same_operation_when_same_key_and_payload_repeat() {
        UUID integration = createIntegration(fastPolicy());
        String client = clientToken(tenant, "operations:read");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("total", 9100);
        payload.put("items", List.of(Map.of("code", "PAN-001", "quantity", "4")));
        Map<String, Object> samePayloadReordered = new LinkedHashMap<>();
        samePayloadReordered.put("items", List.of(Map.of("quantity", "4", "code", "PAN-001")));
        samePayloadReordered.put("total", 9100.0);

        JsonNode first = body(startViaApi(client, integration, "sale-1", payload, 202));
        UUID id = UUID.fromString(first.get("operationId").asString());
        assertThat(reload(id).status()).isEqualTo(OperationStatus.PENDING);

        JsonNode repeated = body(startViaApi(client, integration, "sale-1", samePayloadReordered, 200));

        assertThat(repeated.get("operationId").asString()).isEqualTo(id.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM operations WHERE tenant_id = :t AND idempotency_key = 'sale-1'")
                .param("t", tenant).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void should_return_409_when_same_key_arrives_with_different_payload() {
        UUID integration = createIntegration(fastPolicy());
        String client = clientToken(tenant, "operations:read");
        startViaApi(client, integration, "sale-2", Map.of("total", 9100), 202);

        JsonNode problem = body(startViaApi(client, integration, "sale-2", Map.of("total", 9200), 409));

        assertThat(problem.get("code").asString()).isEqualTo("idempotency-conflict");
    }

    @Test
    void should_create_a_single_operation_when_same_key_races() throws Exception {
        UUID integration = createIntegration(fastPolicy());
        Callable<StartOperation.Started> attempt = () -> startOperation.execute(new StartOperation.Command(
                new TenantId(tenant), integration, OperationKind.CATALOG_SYNC, "race-1", Map.of("a", 1), null));

        List<StartOperation.Started> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<StartOperation.Started>> futures = pool.invokeAll(List.of(attempt, attempt, attempt, attempt));
            results = futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        }

        assertThat(results).filteredOn(StartOperation.Started::created).hasSize(1);
        assertThat(results).extracting(started -> started.operation().id()).containsOnly(results.getFirst().operation().id());
    }
}
