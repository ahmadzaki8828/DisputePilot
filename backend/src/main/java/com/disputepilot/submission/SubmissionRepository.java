package com.disputepilot.submission;

import com.disputepilot.domain.SubmissionAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@code submissions} rows. Statements are single and atomic; the caller never holds a transaction across PayPal. */
@Repository
public class SubmissionRepository {

    private static final String SELECT = """
            select id, draft_id, idempotency_key, action, status, paypal_debug_id, created_at from submissions
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public SubmissionRepository(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    public Optional<Submission> findByKey(String key) {
        return jdbc.sql(SELECT + " where idempotency_key = :k").param("k", key).query(this::map).optional();
    }

    /**
     * Claims the right to call PayPal for this draft: inserts a PENDING row unless the key already exists or
     * the draft already has a PENDING or SUCCEEDED submission under another key. An advisory lock on the draft
     * serialises two concurrent claims.
     *
     * @return the new row's id, or empty when the claim was refused
     */
    public Optional<UUID> claim(UUID draftId, String key, SubmissionAction action, String requestJson,
                                String submittedBy) {
        UUID id = UUID.randomUUID();
        Integer rows = tx.execute(status -> {
            // The lock is taken in its own statement so the insert below sees every claim committed before it.
            jdbc.sql("select pg_advisory_xact_lock(hashtext(cast(:draftId as text)))")
                    .param("draftId", draftId.toString()).query().singleRow();
            return jdbc.sql("""
                    insert into submissions (id, draft_id, idempotency_key, action, request_payload, status,
                        submitted_by)
                    select :id, :draftId, :key, :action, cast(:payload as jsonb), 'PENDING', :by
                    where not exists (select 1 from submissions where draft_id = :draftId
                                      and status in ('PENDING', 'SUCCEEDED'))
                    on conflict (idempotency_key) do nothing
                    """)
                    .param("id", id).param("draftId", draftId).param("key", key).param("action", action.name())
                    .param("payload", requestJson).param("by", submittedBy).update();
        });
        return rows != null && rows == 1 ? Optional.of(id) : Optional.empty();
    }

    /** Re-opens a FAILED submission for a retry with the same key. False if it is no longer FAILED. */
    public boolean reclaimFailed(UUID id, String requestJson) {
        return jdbc.sql("""
                update submissions set status = 'PENDING', paypal_response = null, paypal_debug_id = null,
                    request_payload = cast(:payload as jsonb)
                where id = :id and status = 'FAILED'
                  and not exists (select 1 from submissions s2 where s2.draft_id = submissions.draft_id
                                  and s2.id <> submissions.id and s2.status in ('PENDING', 'SUCCEEDED'))
                """).param("id", id).param("payload", requestJson).update() == 1;
    }

    public void markSucceeded(UUID id, String responseJson) {
        jdbc.sql("update submissions set status = 'SUCCEEDED', paypal_response = cast(:r as jsonb) where id = :id")
                .param("id", id).param("r", responseJson).update();
    }

    public void markFailed(UUID id, String responseJson, String debugId) {
        jdbc.sql("""
                update submissions set status = 'FAILED', paypal_response = cast(:r as jsonb),
                    paypal_debug_id = :debug where id = :id
                """).param("id", id).param("r", responseJson).param("debug", debugId).update();
    }

    public Optional<Submission> find(UUID id) {
        return jdbc.sql(SELECT + " where id = :id").param("id", id).query(this::map).optional();
    }

    private Submission map(ResultSet rs, int i) throws SQLException {
        return new Submission(rs.getObject("id", UUID.class), rs.getObject("draft_id", UUID.class),
                rs.getString("idempotency_key"), SubmissionAction.valueOf(rs.getString("action")),
                Submission.Status.valueOf(rs.getString("status")), rs.getString("paypal_debug_id"),
                rs.getTimestamp("created_at").toInstant());
    }
}
