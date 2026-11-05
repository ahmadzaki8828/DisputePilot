package com.disputepilot.api.analytics;

import com.disputepilot.domain.DisputeReason;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only rows for the AG Studio analytics page: one flat table each for disputes, drafts and submissions.
 * Studio joins them itself (drafts.disputeId to disputes.id, submissions.draftId to drafts.id), so these queries
 * only flatten and label; they never write.
 */
@Repository
public class AnalyticsRepository {

    private final JdbcClient jdbc;

    public AnalyticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param hoursToDue    hours until the seller response is due (negative when overdue); null without a deadline
     * @param dueBucket     open disputes only: "Overdue", "Under 48h", "2-7 days", "Over 7 days" or "No deadline";
     *                      "Closed" otherwise
     * @param moneyAtRisk   the disputed amount while the dispute is open, else 0
     * @param outcomeLabel  "Won", "Lost" or "Pending"
     * @param won           1 when PayPal decided for the seller, else 0 (sum it, divide by decided for win rate)
     * @param decided       1 when PayPal has decided the dispute, else 0
     */
    public record DisputeRow(String id, String paypalDisputeId, boolean simulated, String reason, String reasonLabel,
                             String status, String stage, BigDecimal amount, String currency, String createdAt,
                             String dueAt, Double hoursToDue, String dueBucket, boolean open, BigDecimal moneyAtRisk,
                             String outcome, String outcomeLabel, int won, int decided) {
    }

    /**
     * @param latest             true for the dispute's newest draft that is not superseded
     * @param timeToDraftSeconds seconds from the dispute arriving to this draft being written
     * @param grounded           the grounding validator passed this draft
     */
    public record DraftRow(String id, String disputeId, int version, String status, String recommendation,
                           BigDecimal confidence, String llmMode, String provider, String model, Integer latencyMs,
                           Integer inputTokens, Integer outputTokens, boolean grounded, boolean editedByHuman,
                           boolean latest, String createdAt, Double timeToDraftSeconds) {
    }

    public record SubmissionRow(String id, String draftId, String disputeId, String action, String status,
                                String createdAt) {
    }

    public List<DisputeRow> disputes(Instant now) {
        return jdbc.sql("""
                select id, paypal_dispute_id, simulated, reason, status, life_cycle_stage, amount_value,
                       amount_currency, seller_response_due_at, outcome, created_at
                from disputes order by created_at""")
                .query((rs, i) -> dispute(rs, now)).list();
    }

    public List<DraftRow> drafts() {
        return jdbc.sql("""
                select dr.id, dr.dispute_id, dr.version, dr.status, dr.recommendation, dr.confidence, dr.llm_mode,
                       dr.provider, dr.model, dr.latency_ms, dr.input_tokens, dr.output_tokens,
                       coalesce((dr.validation ->> 'passed')::boolean, false) as grounded, dr.edited_by_human,
                       dr.created_at,
                       extract(epoch from (dr.created_at - d.created_at)) as ttd,
                       dr.version = (select max(x.version) from drafts x
                                     where x.dispute_id = dr.dispute_id and x.status <> 'SUPERSEDED') as latest
                from drafts dr join disputes d on d.id = dr.dispute_id
                order by dr.created_at""")
                .query((rs, i) -> new DraftRow(rs.getString("id"), rs.getString("dispute_id"), rs.getInt("version"),
                        rs.getString("status"), rs.getString("recommendation"), rs.getBigDecimal("confidence"),
                        rs.getString("llm_mode"), rs.getString("provider"), rs.getString("model"),
                        integer(rs, "latency_ms"), integer(rs, "input_tokens"), integer(rs, "output_tokens"),
                        rs.getBoolean("grounded"), rs.getBoolean("edited_by_human"), rs.getBoolean("latest"),
                        iso(rs.getTimestamp("created_at")), nonNegative(rs.getBigDecimal("ttd"))))
                .list();
    }

    public List<SubmissionRow> submissions() {
        return jdbc.sql("""
                select s.id, s.draft_id, dr.dispute_id, s.action, s.status, s.created_at
                from submissions s join drafts dr on dr.id = s.draft_id order by s.created_at""")
                .query((rs, i) -> new SubmissionRow(rs.getString("id"), rs.getString("draft_id"),
                        rs.getString("dispute_id"), rs.getString("action"), rs.getString("status"),
                        iso(rs.getTimestamp("created_at"))))
                .list();
    }

    private static DisputeRow dispute(ResultSet rs, Instant now) throws SQLException {
        String status = rs.getString("status");
        boolean open = !"RESOLVED".equalsIgnoreCase(status);
        Timestamp due = rs.getTimestamp("seller_response_due_at");
        Double hoursToDue = due == null ? null
                : Math.round(Duration.between(now, due.toInstant()).toMinutes() / 6.0) / 10.0;
        String outcome = rs.getString("outcome");
        String outcomeLabel = outcomeLabel(outcome);
        BigDecimal amount = rs.getBigDecimal("amount_value");
        return new DisputeRow(rs.getString("id"), rs.getString("paypal_dispute_id"), rs.getBoolean("simulated"),
                rs.getString("reason"), DisputeReason.labelFor(rs.getString("reason")), status,
                rs.getString("life_cycle_stage"), amount, rs.getString("amount_currency").trim(),
                iso(rs.getTimestamp("created_at")), iso(due), hoursToDue, dueBucket(open, hoursToDue), open,
                open ? amount : BigDecimal.ZERO, outcome, outcomeLabel, "Won".equals(outcomeLabel) ? 1 : 0,
                "Pending".equals(outcomeLabel) ? 0 : 1);
    }

    static String outcomeLabel(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return "Pending";
        }
        String o = outcome.toUpperCase(Locale.ROOT);
        // PayPal spells it both ways; anything that is not the seller's favour counts as lost.
        return o.contains("SELLER_FAVO") ? "Won" : "Lost";
    }

    static String dueBucket(boolean open, Double hoursToDue) {
        if (!open) {
            return "Closed";
        }
        if (hoursToDue == null) {
            return "No deadline";
        }
        if (hoursToDue < 0) {
            return "Overdue";
        }
        if (hoursToDue < 48) {
            return "Under 48h";
        }
        return hoursToDue <= 7 * 24 ? "2-7 days" : "Over 7 days";
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Double nonNegative(BigDecimal seconds) {
        return seconds == null ? null : Math.max(0d, seconds.doubleValue());
    }
}
