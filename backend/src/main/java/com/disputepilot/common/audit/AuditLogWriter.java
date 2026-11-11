package com.disputepilot.common.audit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one writer of {@code audit_log}. Every state change DisputePilot makes or observes gets a row (plan §11),
 * so the dashboard can show how a dispute reached its current state: webhooks, syncs, drafts, approvals,
 * submissions and demo actions all go through here.
 */
@Component
public class AuditLogWriter {

    public static final String ACTOR_SYSTEM = "system";
    public static final String ACTOR_AGENT = "agent";
    public static final String ACTOR_USER = "user";

    // Webhook and sync pipeline
    public static final String WEBHOOK_RECEIVED = "WEBHOOK_RECEIVED";
    public static final String DISPUTE_SYNCED = "DISPUTE_SYNCED";
    public static final String DISPUTES_RECONCILED = "DISPUTES_RECONCILED";
    // Drafts
    public static final String DRAFT_REQUESTED = "DRAFT_REQUESTED";
    public static final String DRAFT_CREATED = "DRAFT_CREATED";
    public static final String DRAFT_FAILED = "DRAFT_FAILED";
    public static final String DRAFT_EDITED = "DRAFT_EDITED";
    public static final String DRAFT_APPROVED = "DRAFT_APPROVED";
    // Submissions
    public static final String SUBMISSION_STARTED = "SUBMISSION_STARTED";
    public static final String SUBMISSION_SUCCEEDED = "SUBMISSION_SUCCEEDED";
    public static final String SUBMISSION_FAILED = "SUBMISSION_FAILED";
    public static final String SUBMISSION_REPLAYED = "SUBMISSION_REPLAYED";
    // Demo and dev
    public static final String DEMO_DISPUTE_CREATED = "DEMO_DISPUTE_CREATED";
    public static final String DEMO_RESET = "DEMO_RESET";
    public static final String TRANSACTION_LINKED = "TRANSACTION_LINKED";
    public static final String MANIPULATION_FLAGGED = "MANIPULATION_FLAGGED";
    public static final String LLM_FALLBACK = "LLM_FALLBACK";

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

    public void agent(String action, UUID disputeId, Map<String, ?> details) {
        record(ACTOR_AGENT, action, disputeId, details);
    }

    public void user(String action, UUID disputeId, Map<String, ?> details) {
        record(ACTOR_USER, action, disputeId, details);
    }

    /** One audit row as the API returns it. */
    public record Entry(long id, java.time.Instant at, String actor, String action, UUID disputeId, JsonNode details) {
    }

    /** Audit entries, oldest first, optionally for one dispute. */
    public List<Entry> list(UUID disputeId) {
        String sql = """
                select id, at, actor, action, dispute_id, details::text as details from audit_log
                %s order by id asc
                """.formatted(disputeId == null ? "" : "where dispute_id = :disputeId");
        var spec = jdbc.sql(sql);
        if (disputeId != null) {
            spec = spec.param("disputeId", disputeId);
        }
        return spec.query((rs, i) -> new Entry(rs.getLong("id"), rs.getTimestamp("at").toInstant(),
                rs.getString("actor"), rs.getString("action"), rs.getObject("dispute_id", UUID.class),
                json.readTree(rs.getString("details")))).list();
    }
}
