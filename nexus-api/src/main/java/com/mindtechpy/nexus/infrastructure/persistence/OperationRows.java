package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.domain.operation.ConnectorError;
import com.mindtechpy.nexus.domain.operation.ErrorKind;
import com.mindtechpy.nexus.domain.operation.Operation;
import com.mindtechpy.nexus.domain.operation.OperationKind;
import com.mindtechpy.nexus.domain.operation.OperationStatus;
import com.mindtechpy.nexus.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.mindtechpy.nexus.infrastructure.persistence.JdbcValues.*;

/** Row mapping of {@code operations}, shared by the repository and the job queue. */
final class OperationRows {

    private OperationRows() {
    }

    static RowMapper<Operation> mapper(JsonMapper json) {
        return (rs, n) -> new Operation(uuid(rs, "id"), new TenantId(uuid(rs, "tenant_id")), uuid(rs, "integration_id"),
                OperationKind.valueOf(rs.getString("kind")), rs.getString("idempotency_key"), rs.getString("request_hash"),
                OperationStatus.valueOf(rs.getString("status")), rs.getString("current_step"), rs.getInt("attempts"),
                rs.getInt("max_attempts"), instant(rs, "next_run_at"), rs.getString("locked_by"),
                rs.getString("correlation_id"), map(json, rs.getString("payload")), map(json, rs.getString("result")),
                error(map(json, rs.getString("last_error"))), rs.getString("dead_reason"), instant(rs, "created_at"),
                instant(rs, "updated_at"), instant(rs, "finished_at"));
    }

    static Map<String, Object> errorJson(ConnectorError error) {
        if (error == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", error.kind().name());
        value.put("code", error.code());
        value.put("detail", error.detail());
        value.put("httpStatus", error.httpStatus());
        value.put("retryAfterSeconds", error.retryAfter() == null ? null : error.retryAfter().toSeconds());
        return value;
    }

    private static ConnectorError error(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Number httpStatus = (Number) value.get("httpStatus");
        Number retryAfter = (Number) value.get("retryAfterSeconds");
        return new ConnectorError(ErrorKind.valueOf((String) value.get("kind")), (String) value.get("code"),
                (String) value.get("detail"), httpStatus == null ? null : httpStatus.intValue(),
                retryAfter == null ? null : Duration.ofSeconds(retryAfter.longValue()));
    }
}
