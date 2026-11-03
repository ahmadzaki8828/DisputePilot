package com.disputepilot.api;

import com.disputepilot.domain.DisputeReason;
import com.disputepilot.domain.Money;
import com.disputepilot.paypal.PayPalDispute;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class DisputeRepository {

    private static final String SELECT = """
            select d.id, d.paypal_dispute_id, d.simulated, d.reason, d.status, d.life_cycle_stage,
                   d.amount_value, d.amount_currency, d.seller_response_due_at, d.disputed_transaction_id,
                   d.requested_evidence::text as requested_evidence, d.allowed_actions, d.raw::text as raw,
                   ld.recommendation, ld.confidence, ld.status as draft_status
            from disputes d
            left join lateral (
                select recommendation, confidence, status from drafts
                where dispute_id = d.id and status <> 'SUPERSEDED'
                order by version desc limit 1) ld on true
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DisputeRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Insert or update a dispute keyed by paypal_dispute_id. Returns the internal id. */
    public UUID upsert(PayPalDispute d) {
        return jdbc.sql("""
                insert into disputes (id, paypal_dispute_id, simulated, reason, status, life_cycle_stage,
                    amount_value, amount_currency, seller_response_due_at, disputed_transaction_id,
                    requested_evidence, allowed_actions, outcome, raw, paypal_update_time)
                values (:id, :pid, :sim, :reason, :status, :stage, :amt, :cur, :due, :txn,
                    cast(:req as jsonb), cast(:actions as text[]), :outcome, cast(:raw as jsonb), :upd)
                on conflict (paypal_dispute_id) do update set
                    simulated = excluded.simulated, reason = excluded.reason, status = excluded.status,
                    life_cycle_stage = excluded.life_cycle_stage, amount_value = excluded.amount_value,
                    amount_currency = excluded.amount_currency, seller_response_due_at = excluded.seller_response_due_at,
                    disputed_transaction_id = excluded.disputed_transaction_id,
                    requested_evidence = excluded.requested_evidence, allowed_actions = excluded.allowed_actions,
                    outcome = excluded.outcome, raw = excluded.raw, paypal_update_time = excluded.paypal_update_time,
                    updated_at = now()
                returning id
                """)
                .param("id", UUID.randomUUID())
                .param("pid", d.paypalDisputeId())
                .param("sim", d.simulated())
                .param("reason", d.reason())
                .param("status", d.status())
                .param("stage", d.lifeCycleStage())
                .param("amt", d.amount().value())
                .param("cur", d.amount().currency())
                .param("due", d.sellerResponseDueAt() == null ? null : java.sql.Timestamp.from(d.sellerResponseDueAt()))
                .param("txn", d.disputedTransactionId())
                .param("req", json.writeValueAsString(d.requestedEvidence()))
                .param("actions", pgTextArray(d.allowedActions()))
                .param("outcome", d.outcome())
                .param("raw", json.writeValueAsString(d.raw()))
                .param("upd", d.updateTime() == null ? null : java.sql.Timestamp.from(d.updateTime()))
                .query(UUID.class)
                .single();
    }

    public List<DisputeSummary> list(String status, String reason, Integer dueWithinHours, Boolean simulated) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            sql.append(" and d.status = :status");
            params.put("status", status);
        }
        if (reason != null && !reason.isBlank()) {
            sql.append(" and d.reason = :reason");
            params.put("reason", reason);
        }
        if (dueWithinHours != null) {
            sql.append(" and d.seller_response_due_at <= now() + make_interval(hours => :hours)");
            params.put("hours", dueWithinHours);
        }
        if (simulated != null) {
            sql.append(" and d.simulated = :simulated");
            params.put("simulated", simulated);
        }
        sql.append(" order by d.seller_response_due_at asc nulls last, d.created_at desc");
        return jdbc.sql(sql.toString()).params(params).query((rs, i) -> summary(rs)).list();
    }

    public Optional<StoredDispute> find(UUID id) {
        return jdbc.sql(SELECT + " where d.id = :id").param("id", id)
                .query((rs, i) -> new StoredDispute(summary(rs), strings(rs.getString("requested_evidence")),
                        textArray(rs.getArray("allowed_actions")), rs.getString("disputed_transaction_id"),
                        parse(rs.getString("raw"))))
                .optional();
    }

    public Optional<UUID> findIdByPaypalDisputeId(String paypalDisputeId) {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :p").param("p", paypalDisputeId)
                .query(UUID.class).optional();
    }

    public int countSimulated() {
        return jdbc.sql("select count(*) from disputes where simulated").query(Integer.class).single();
    }

    /**
     * Deletes every simulated dispute with its submissions, drafts, audit entries and webhook events (the real
     * sandbox disputes are left alone). Returns how many disputes were removed.
     */
    public int deleteSimulated() {
        jdbc.sql("delete from submissions where draft_id in (select dr.id from drafts dr join disputes d "
                + "on d.id = dr.dispute_id where d.simulated)").update();
        jdbc.sql("delete from drafts where dispute_id in (select id from disputes where simulated)").update();
        jdbc.sql("delete from audit_log where dispute_id in (select id from disputes where simulated)").update();
        jdbc.sql("delete from webhook_events where resource_id like 'DEMO-%'").update();
        return jdbc.sql("delete from disputes where simulated").update();
    }

    public record StoredDispute(DisputeSummary summary, List<String> requestedEvidence, List<String> allowedActions,
                                String disputedTransactionId, JsonNode raw) {
    }

    private DisputeSummary summary(ResultSet rs) throws SQLException {
        String rec = rs.getString("recommendation");
        DisputeSummary.LatestDraft draft = rec == null ? null
                : new DisputeSummary.LatestDraft(rec, rs.getBigDecimal("confidence"), rs.getString("draft_status"));
        java.sql.Timestamp due = rs.getTimestamp("seller_response_due_at");
        String reason = rs.getString("reason");
        return new DisputeSummary(
                rs.getObject("id", UUID.class),
                rs.getString("paypal_dispute_id"),
                rs.getBoolean("simulated"),
                reason,
                DisputeReason.labelFor(reason),
                rs.getString("status"),
                rs.getString("life_cycle_stage"),
                new Money(rs.getString("amount_currency").trim(), rs.getBigDecimal("amount_value")),
                due == null ? null : Instant.ofEpochMilli(due.getTime()),
                draft);
    }

    private JsonNode parse(String s) {
        return json.readTree(s);
    }

    private List<String> strings(String jsonArray) {
        List<String> out = new ArrayList<>();
        parse(jsonArray).forEach(n -> out.add(n.asString()));
        return out;
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    private static String pgTextArray(List<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }
}
