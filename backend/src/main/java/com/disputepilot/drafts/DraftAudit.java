package com.disputepilot.drafts;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes audit_log rows for everything that happens to a draft. Every state change is recorded. */
@Component
public class DraftAudit {

    public static final String DRAFT_REQUESTED = "DRAFT_REQUESTED";
    public static final String DRAFT_CREATED = "DRAFT_CREATED";
    public static final String DRAFT_FAILED = "DRAFT_FAILED";
    public static final String DRAFT_EDITED = "DRAFT_EDITED";
    public static final String DRAFT_APPROVED = "DRAFT_APPROVED";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DraftAudit(JdbcClient jdbc, JsonMapper json) {
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
}
