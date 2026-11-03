package com.disputepilot.sync;

import com.disputepilot.api.DisputeController;
import com.disputepilot.api.DisputeDetail;
import com.disputepilot.api.DisputeRepository;
import com.disputepilot.common.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/disputes/{id}/sync} and {@code POST /api/sync} (plan §7): pull one dispute, or run
 * reconciliation, on demand. Both routes need the admin token like every other {@code /api/**} route.
 */
@RestController
@RequestMapping("/api")
public class SyncController {

    public static final String MANUAL = "MANUAL_SYNC";

    private final DisputeRepository disputes;
    private final DisputeSyncService sync;
    private final ReconciliationPoller poller;
    private final DisputeController details;

    public SyncController(DisputeRepository disputes, DisputeSyncService sync, ReconciliationPoller poller,
            DisputeController details) {
        this.disputes = disputes;
        this.sync = sync;
        this.poller = poller;
        this.details = details;
    }

    /** Re-fetch one dispute from PayPal and return the refreshed detail view. */
    @PostMapping("/disputes/{id}/sync")
    public DisputeDetail syncOne(@PathVariable UUID id) {
        String paypalDisputeId = disputes.find(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dispute not found",
                        "No dispute with id " + id))
                .summary().paypalDisputeId();
        sync.sync(paypalDisputeId, MANUAL);
        // Reuse the one place that assembles a detail view, so this route cannot drift from GET /api/disputes/{id}.
        return details.get(id);
    }

    /** Run reconciliation now instead of waiting for the next scheduled run. */
    @PostMapping("/sync")
    public Map<String, Object> syncAll() {
        ReconciliationPoller.Summary summary = poller.runOnce();
        return Map.of(
                "listed", summary.listed(),
                "synced", summary.synced(),
                "failed", summary.failed(),
                "cursor", summary.cursor().toString());
    }
}
