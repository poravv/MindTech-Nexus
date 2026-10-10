package com.mindtechpy.nexus.infrastructure.persistence;

import com.mindtechpy.nexus.application.port.out.JobQueuePort;
import com.mindtechpy.nexus.domain.operation.Operation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * The {@code operations} table as a queue (spec §7.2). The claim is one short statement: rows locked by
 * another worker are skipped, so concurrent workers never lease the same operation. Operations of
 * integrations that do not dispatch (PAUSED, DISABLED, EMERGENCY_STOP) stay PENDING/RETRY untouched.
 */
@Component
class PostgresJobQueue implements JobQueuePort {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    PostgresJobQueue(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Operation> claim(String workerId, int limit) {
        return jdbc.sql("""
                        WITH next AS (
                          SELECT o.id FROM operations o JOIN integrations i ON i.id = o.integration_id
                          WHERE o.status IN ('PENDING', 'RETRY') AND o.next_run_at <= now()
                            AND i.state IN ('ENABLED', 'DRAINING')
                          ORDER BY o.priority, o.next_run_at
                          LIMIT :limit FOR UPDATE OF o SKIP LOCKED)
                        UPDATE operations o SET status = 'RUNNING', locked_by = :worker,
                               locked_until = now() + interval '10 minutes', attempts = o.attempts + 1, updated_at = now()
                        FROM next WHERE o.id = next.id
                        RETURNING o.*""")
                .param("limit", limit)
                .param("worker", workerId)
                .query(OperationRows.mapper(json)).list();
    }

    @Override
    public int rescueExpiredLeases() {
        return jdbc.sql("""
                        UPDATE operations SET status = 'RETRY', locked_by = NULL, locked_until = NULL, next_run_at = now(),
                               updated_at = now()
                        WHERE status = 'RUNNING' AND locked_until < now()""")
                .update();
    }
}
