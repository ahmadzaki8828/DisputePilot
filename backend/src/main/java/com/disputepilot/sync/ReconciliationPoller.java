package com.disputepilot.sync;

import com.disputepilot.paypal.DisputePage;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.PayPalApiException;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.PayPalDisputesClient;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lists disputes PayPal has touched since the stored cursor and re-syncs each one, so a webhook that was never
 * delivered (or was stored as FAILED) heals itself within one polling interval (plan §8.3).
 *
 * <p>The cursor lives in {@code sync_state} and only ever moves forward. The first run looks back
 * {@code dp.sync.poll-lookback}.
 */
@Component
public class ReconciliationPoller {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationPoller.class);
    public static final String CHANGE_TYPE = "RECONCILED";

    private final PayPalDisputesClient paypal;
    private final DisputeSyncService sync;
    private final SyncStateRepository state;
    private final AuditLogWriter audit;
    private final SyncProperties props;

    public ReconciliationPoller(PayPalDisputesClient paypal, DisputeSyncService sync, SyncStateRepository state,
            AuditLogWriter audit, SyncProperties props) {
        this.paypal = paypal;
        this.sync = sync;
        this.state = state;
        this.audit = audit;
        this.props = props;
    }

    /** What one reconciliation run did. */
    public record Summary(int listed, int synced, int failed, Instant cursor) {
    }

    @Scheduled(fixedDelayString = "${dp.sync.poll-interval:PT10M}", initialDelayString = "PT30S")
    void scheduled() {
        if (!props.schedulingEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled, so nothing may escape here.
            log.error("Reconciliation run failed", e);
        }
    }

    /** Run reconciliation now; also the body of {@code POST /api/sync}. */
    public Summary runOnce() {
        Instant from = state.cursor().orElseGet(() -> Instant.now().minus(props.pollLookback()));
        int listed = 0;
        int synced = 0;
        int failed = 0;
        Instant highWater = from;
        String pageToken = null;

        for (int page = 0; page < props.pollMaxPages(); page++) {
            DisputePage result;
            try {
                result = paypal.listDisputes(new DisputeQuery(null, from, props.pollPageSize(), pageToken));
            } catch (PayPalApiException e) {
                log.warn("Reconciliation could not list disputes updated after {}: {}", from, e);
                break;
            }
            for (PayPalDispute item : result.items()) {
                listed++;
                try {
                    PayPalDispute fresh = sync.sync(item.paypalDisputeId(), CHANGE_TYPE).dispute();
                    synced++;
                    if (fresh.updateTime() != null && fresh.updateTime().isAfter(highWater)) {
                        highWater = fresh.updateTime();
                    }
                } catch (RuntimeException e) {
                    // One unreachable dispute must not stop the rest, and must not advance the cursor past it.
                    failed++;
                    log.warn("Reconciliation failed for {}: {}", item.paypalDisputeId(), e.toString());
                }
            }
            pageToken = result.nextPageToken();
            if (pageToken == null || pageToken.isBlank()) {
                break;
            }
        }

        // Only move the cursor when every listed dispute was stored, so nothing is skipped on the next run.
        if (failed == 0) {
            state.advanceCursor(highWater);
        }
        Summary summary = new Summary(listed, synced, failed, state.cursor().orElse(from));
        if (listed > 0 || failed > 0) {
            audit.system("DISPUTES_RECONCILED", null, Map.of(
                    "listed", listed, "synced", synced, "failed", failed, "cursor", summary.cursor().toString()));
            log.info("Reconciliation listed {} dispute(s), synced {}, failed {}, cursor now {}", listed, synced,
                    failed, summary.cursor());
        }
        return summary;
    }
}
