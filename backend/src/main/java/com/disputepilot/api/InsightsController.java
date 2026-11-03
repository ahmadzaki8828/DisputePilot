package com.disputepilot.api;

import com.disputepilot.common.audit.AuditLogWriter;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/audit} and {@code GET /api/metrics} (plan §7). */
@RestController
public class InsightsController {

    private final AuditLogWriter audit;
    private final JdbcClient jdbc;

    public InsightsController(AuditLogWriter audit, JdbcClient jdbc) {
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/audit")
    public List<AuditLogWriter.Entry> audit(@RequestParam(required = false) UUID disputeId) {
        return audit.list(disputeId);
    }

    public record Metrics(int openDisputes, int dueWithin48h, int draftsReady, int submitted,
                          Double meanTimeToDraftSeconds, Double winRate) {
    }

    @GetMapping("/api/metrics")
    public Metrics metrics() {
        int open = count("select count(*) from disputes where status <> 'RESOLVED'");
        int due = count("""
                select count(*) from disputes where status = 'WAITING_FOR_SELLER_RESPONSE'
                  and seller_response_due_at <= now() + interval '48 hours'""");
        int ready = count("select count(*) from drafts where status in ('DRAFT', 'NEEDS_REVIEW')");
        int submitted = count("select count(*) from submissions where status = 'SUCCEEDED'");
        BigDecimal ttd = jdbc.sql("""
                select avg(extract(epoch from (d1.created_at - d.created_at)))
                from disputes d join drafts d1 on d1.dispute_id = d.id and d1.version = 1""")
                .query(BigDecimal.class).optional().orElse(null);
        // Win rate over disputes PayPal has decided: seller-favour outcomes / all decided outcomes.
        int decided = count("select count(*) from disputes where outcome is not null");
        int won = count("select count(*) from disputes where outcome ilike '%SELLER_FAVO%'");
        return new Metrics(open, due, ready, submitted,
                ttd == null ? null : Math.max(0d, ttd.doubleValue()),
                decided == 0 ? null : (double) won / decided);
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
