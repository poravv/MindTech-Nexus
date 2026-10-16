package com.mindtechpy.nexus.infrastructure.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Daily retention (spec §6): clears {@code operations.payload} 30 days after the operation succeeded
 * ({@code result} stays) and deletes {@code inbound_events} older than 90 days. FAILED and DEAD operations
 * keep their payload: a replay or a repeated request re-runs the saga from it, however late. It also deletes
 * {@code CATALOG_SYNC} operations that SUCCEEDED more than 7 days ago, with their steps (the scheduler creates
 * one every few minutes): no other kind or status. Their keys embed the window time, so a deleted key is never
 * asked for again, and the catalog backoff only reads the latest runs.
 */
public class PayloadRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PayloadRetentionJob.class);

    private final JdbcClient jdbc;

    public PayloadRetentionJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purge() {
        int payloads = jdbc.sql("""
                UPDATE operations SET payload = NULL
                WHERE payload IS NOT NULL AND status = 'SUCCEEDED' AND finished_at < now() - interval '30 days'""").update();
        int events = jdbc.sql("DELETE FROM inbound_events WHERE received_at < now() - interval '90 days'").update();
        int catalogRuns = jdbc.sql("""
                WITH doomed AS (
                    SELECT id FROM operations
                    WHERE kind = 'CATALOG_SYNC' AND status = 'SUCCEEDED' AND finished_at < now() - interval '7 days'),
                deleted_steps AS (
                    DELETE FROM operation_steps WHERE operation_id IN (SELECT id FROM doomed)),
                detached_events AS (
                    UPDATE inbound_events SET operation_id = NULL WHERE operation_id IN (SELECT id FROM doomed))
                DELETE FROM operations WHERE id IN (SELECT id FROM doomed)""").update();
        log.info("Retención: {} payloads vaciados, {} eventos entrantes eliminados, {} corridas de catálogo eliminadas",
                payloads, events, catalogRuns);
    }
}
