package com.disputepilot.sync;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends to {@code audit_log}. Every state change that DisputePilot makes or observes gets a row, so the
 * dashboard can show how a dispute reached its current state.
 *
 * <p>Created by Session A for the webhook and sync pipelines; it is deliberately generic, so later sessions can
 * reuse it (or move it to {@code common/}) for draft and submission events rather than writing their own.
 */
@Component
public class AuditLogWriter {

    public static final String ACTOR_SYSTEM = "system";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLogWriter(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, String action, UUID disputeId, Map<String, ?> details) {
        jdbc.sql("""
                insert into audit_log (actor, action, dispute_id, details)
                values (:actor, :action, :disputeId, cast(:details as jsonb))
                """)
                .param("actor", actor)
                .param("action", action)
                .param("disputeId", disputeId)
                .param("details", json.writeValueAsString(details == null ? Map.of() : details))
                .update();
    }

    public void system(String action, UUID disputeId, Map<String, ?> details) {
        record(ACTOR_SYSTEM, action, disputeId, details);
    }
}
