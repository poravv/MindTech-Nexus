package com.mindtechpy.nexus.it;

import com.mindtechpy.nexus.application.port.out.JobQueuePort;
import com.mindtechpy.nexus.application.port.out.OperationRepository;
import com.mindtechpy.nexus.application.saga.SagaRunner;
import com.mindtechpy.nexus.application.usecase.StartOperation;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.shared.TenantId;
import com.mindtechpy.nexus.it.TestSagaConfig.ScriptedSaga;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Engine tests: each test gets a fresh tenant with a GiftHub target connection and drives the queue by
 * hand ({@link #drain}) with the {@link ScriptedSaga}. Operations of other tests that happen to be due
 * are processed too and ignored by the assertions, which always filter by the test's own ids.
 */
abstract class AbstractOperationIT extends AbstractPostgresIT {

    @Autowired
    protected ScriptedSaga saga;
    @Autowired
    protected JobQueuePort queue;
    @Autowired
    protected SagaRunner runner;
    @Autowired
    protected OperationRepository operations;
    @Autowired
    protected StartOperation startOperation;

    protected String admin;
    protected UUID tenant;
    protected UUID targetConnection;

    @BeforeEach
    void setUpTenant() {
        saga.reset();
        admin = platformAdminToken();
        tenant = createTenant(admin);
        targetConnection = UUID.fromString(body(perform(post("/api/admin/connections").param("tenantId", tenant.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("connector", "GIFTHUB", "name", "gifthub",
                        "baseUrl", "http://api.comercio-central.svc.cluster.local:3000",
                        "credentials", Map.of("apiKey", "gh_live_test")))), 201)).get("id").asString());
    }

    /** Generous limits so that only the test's own scenario throttles. */
    protected static Map<String, Object> fastPolicy() {
        Map<String, Object> policy = new HashMap<>();
        policy.put("ratePerSecond", 1000);
        policy.put("burst", 1000);
        policy.put("maxInFlight", 100);
        policy.put("maxAttempts", 3);
        policy.put("baseDelaySeconds", 2);
        policy.put("maxDelaySeconds", 8);
        return policy;
    }

    protected UUID createIntegration(Map<String, Object> policy) {
        return UUID.fromString(createIntegration(tenant, admin, targetConnection, policy, 201).get("id").asString());
    }

    protected JsonNode createIntegration(UUID tenantId, String token, UUID target, Map<String, Object> policy, int expected) {
        Map<String, Object> request = new HashMap<>();
        request.put("flow", "CATALOG");
        request.put("code", "catalog-" + UUID.randomUUID().toString().substring(0, 8));
        request.put("targetConnectionId", target.toString());
        request.put("policy", policy);
        return body(perform(post("/api/admin/integrations").param("tenantId", tenantId.toString())
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(request)), expected));
    }

    protected MvcResult changeState(UUID integrationId, String token, String state, int expected) {
        return perform(post("/api/admin/integrations/{id}/state", integrationId).param("tenantId", tenant.toString())
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("state", state, "reason", "prueba " + state))), expected);
    }

    protected Operation start(UUID integrationId, String key) {
        return startOperation.execute(new StartOperation.Command(new TenantId(tenant), integrationId,
                OperationKind.CATALOG_SYNC, key, Map.of("key", key), "corr-" + key)).operation();
    }

    /** Claims and runs everything due until the queue has nothing left to hand out. */
    protected void drain() {
        List<Operation> claimed;
        while (!(claimed = claimDue("it-worker", 10)).isEmpty()) {
            claimed.forEach(runner::run);
        }
    }

    /**
     * {@code next_run_at} is written with the JVM clock and compared with Postgres {@code now()}; the Docker VM
     * clock can lag the JVM by a millisecond or two, so an operation scheduled "now" may not be due yet for
     * the database. Operations due within {@value #CLOCK_SKEW_MS} ms are pulled to {@code now()} before
     * claiming; real backoffs (seconds) are untouched.
     */
    protected List<Operation> claimDue(String worker, int limit) {
        jdbc.sql("""
                UPDATE operations SET next_run_at = now()
                WHERE status IN ('PENDING', 'RETRY') AND next_run_at > now()
                  AND next_run_at <= now() + make_interval(secs => :skew / 1000.0)""").param("skew", CLOCK_SKEW_MS).update();
        return queue.claim(worker, limit);
    }

    private static final int CLOCK_SKEW_MS = 100;

    /** Claims as {@code worker} and keeps the leases of {@code ids}; any other due operation is run normally. */
    protected List<Operation> claimOnly(Set<UUID> ids, String worker) {
        List<Operation> ours = new ArrayList<>();
        for (Operation claimed : claimDue(worker, 200)) {
            if (ids.contains(claimed.id())) {
                ours.add(claimed);
            } else {
                runner.run(claimed);
            }
        }
        assertThat(ours).extracting(Operation::id).containsExactlyInAnyOrderElementsOf(ids);
        return ours;
    }

    protected Operation claimOnly(UUID id, String worker) {
        return claimOnly(Set.of(id), worker).getFirst();
    }

    protected Operation reload(UUID id) {
        return operations.findById(new TenantId(tenant), id).orElseThrow();
    }

    protected void makeDue(UUID id) {
        jdbc.sql("UPDATE operations SET next_run_at = now() - interval '1 second' WHERE id = :id").param("id", id).update();
    }

    /** Machine token of {@code tenantId} with the given scopes, through the real token endpoint. */
    protected String clientToken(UUID tenantId, String... scopes) {
        JsonNode issued = body(perform(post("/api/admin/api-clients").param("tenantId", tenantId.toString())
                .header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("name", "client", "scopes", List.of(scopes)))), 201));
        String basic = Base64.getEncoder().encodeToString((issued.get("client").get("clientId").asString() + ":"
                + issued.get("clientSecret").asString()).getBytes());
        return body(perform(post("/oauth/token").header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials"), 200))
                .get("access_token").asString();
    }

    protected MvcResult startViaApi(String clientToken, UUID integrationId, String key, Map<String, Object> payload,
                                    int expected) {
        return perform(post("/api/v1/test-operations").header("Authorization", bearer(clientToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("integrationId", integrationId.toString(), "key", key, "payload", payload))), expected);
    }
}
