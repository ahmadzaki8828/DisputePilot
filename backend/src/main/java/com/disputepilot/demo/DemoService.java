package com.disputepilot.demo;

import com.disputepilot.api.DisputeRepository;
import com.disputepilot.api.DisputeSummary;
import com.disputepilot.common.ApiException;
import com.disputepilot.common.audit.AuditLogWriter;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import com.disputepilot.sync.DisputeSyncService;
import com.disputepilot.webhook.DisputeOutboxWorker;
import com.disputepilot.webhook.WebhookIngestService;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a judge can do on the public demo (plan §13 D.3): create a simulated {@code DEMO-} dispute that travels the
 * same webhook -> outbox -> sync -> agent path as a real one, and reset the demo to its starter set.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);
    private static final String CREATED = "CUSTOMER.DISPUTE.CREATED";

    /** Contract's DemoTemplate -> the fixture it clones (and whose recorded AI response it replays). */
    static final Map<String, String> TEMPLATES = Map.of(
            "ITEM_NOT_RECEIVED", "DEMO-D-1001",
            "NOT_AS_DESCRIBED", "DEMO-D-1003",
            "UNAUTHORISED", "DEMO-D-1004",
            "DUPLICATE_CHARGE", "DEMO-D-1006",
            "PROMPT_INJECTION", "DEMO-D-2005");

    private final SimulatedPayPalDisputesClient simulated;
    private final WebhookIngestService ingest;
    private final DisputeOutboxWorker outbox;
    private final DisputeSyncService sync;
    private final DisputeRepository disputes;
    private final AuditLogWriter audit;

    public DemoService(SimulatedPayPalDisputesClient simulated, WebhookIngestService ingest,
                       DisputeOutboxWorker outbox, DisputeSyncService sync, DisputeRepository disputes,
                       AuditLogWriter audit) {
        this.simulated = simulated;
        this.ingest = ingest;
        this.outbox = outbox;
        this.sync = sync;
        this.disputes = disputes;
        this.audit = audit;
    }

    /** Creates a simulated dispute and pushes its CREATED event through the real webhook pipeline. */
    public DisputeSummary createDispute(String template) {
        String templateId = TEMPLATES.get(template == null ? "" : template);
        if (templateId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown template",
                    "template must be one of " + TEMPLATES.keySet() + ".");
        }
        PayPalDispute dispute = simulated.createFromTemplate(templateId);
        ingest.ingestSimulatedEvent(CREATED, dispute);
        outbox.processPending(); // the scheduled worker would get there within seconds; the caller wants the row now
        UUID id = awaitDispute(dispute.paypalDisputeId());
        audit.user(AuditLogWriter.DEMO_DISPUTE_CREATED, id, Map.of("template", template,
                "paypalDisputeId", dispute.paypalDisputeId()));
        return disputes.find(id).orElseThrow().summary();
    }

    /** Deletes the simulated disputes and re-seeds the starter set. Real sandbox disputes are untouched. */
    @Transactional
    public int reset() {
        int removed = disputes.deleteSimulated();
        int seeded = seedStarters();
        audit.user(AuditLogWriter.DEMO_RESET, null, Map.of("removed", removed, "seeded", seeded));
        return seeded;
    }

    /** Loads the six starter disputes through the same sync path a webhook uses. */
    @Transactional
    public int seedStarters() {
        simulated.resetToFixtures();
        int count = 0;
        for (PayPalDispute d : simulated.listDisputes(DisputeQuery.all()).items()) {
            sync.sync(d.paypalDisputeId(), "SEEDED");
            count++;
        }
        log.info("Seeded {} starter disputes", count);
        return count;
    }

    private UUID awaitDispute(String paypalDisputeId) {
        // processPending() normally stored it; if the scheduled worker claimed the event first, wait for its commit.
        for (int i = 0; i < 40; i++) {
            var id = disputes.findIdByPaypalDisputeId(paypalDisputeId);
            if (id.isPresent()) {
                return id.get();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Dispute not ingested yet",
                "The simulated webhook was stored but not processed in time; refresh in a few seconds.");
    }
}
