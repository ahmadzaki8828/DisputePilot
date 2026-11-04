package com.disputepilot.sync;

import com.disputepilot.common.audit.AuditLogWriter;
import com.disputepilot.api.DisputeRepository;
import com.disputepilot.domain.DisputeChangedEvent;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.PayPalDisputesClient;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one way a dispute row is refreshed: re-fetch the dispute from PayPal, upsert it, write an audit entry and
 * publish a {@link DisputeChangedEvent}.
 *
 * <p>PayPal is the source of truth (plan §3): a webhook delivery or a poller page is only a trigger, and the
 * payload that triggered the refresh is never written into {@code disputes} directly.
 */
@Service
public class DisputeSyncService {

    private static final Logger log = LoggerFactory.getLogger(DisputeSyncService.class);

    private final PayPalDisputesClient paypal;
    private final DisputeRepository disputes;
    private final AuditLogWriter audit;
    private final ApplicationEventPublisher events;

    public DisputeSyncService(PayPalDisputesClient paypal, DisputeRepository disputes, AuditLogWriter audit,
            ApplicationEventPublisher events) {
        this.paypal = paypal;
        this.disputes = disputes;
        this.audit = audit;
        this.events = events;
    }

    /** What one refresh produced. */
    public record SyncResult(UUID disputeId, PayPalDispute dispute) {
    }

    /**
     * Re-fetch {@code paypalDisputeId} and store it.
     *
     * @param changeType what triggered this refresh; recorded in the audit trail and on the published event
     */
    @Transactional
    public SyncResult sync(String paypalDisputeId, String changeType) {
        PayPalDispute dispute = paypal.getDispute(paypalDisputeId);
        UUID id = disputes.upsert(dispute);

        Map<String, Object> details = new HashMap<>();
        details.put("paypalDisputeId", dispute.paypalDisputeId());
        details.put("changeType", changeType);
        details.put("status", dispute.status());
        details.put("reason", dispute.reason());
        details.put("simulated", dispute.simulated());
        details.put("allowedActions", dispute.allowedActions());
        audit.system(AuditLogWriter.DISPUTE_SYNCED, id, details);

        log.info("Synced dispute {} ({}): status={} simulated={} trigger={}", dispute.paypalDisputeId(), id,
                dispute.status(), dispute.simulated(), changeType);
        events.publishEvent(new DisputeChangedEvent(id, changeType));
        return new SyncResult(id, dispute);
    }
}
