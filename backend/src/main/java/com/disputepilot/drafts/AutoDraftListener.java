package com.disputepilot.drafts;

import com.disputepilot.api.DisputeRepository;
import com.disputepilot.domain.DisputeChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Drafts a response as soon as a dispute arrives or changes, so the merchant finds an answer waiting instead
 * of a task (plan §13 B step 6). Only for disputes PayPal says the seller can still act on, and only when
 * there is no draft already.
 */
@Component
public class AutoDraftListener {

    private static final Logger log = LoggerFactory.getLogger(AutoDraftListener.class);
    private static final String WAITING_FOR_SELLER = "WAITING_FOR_SELLER_RESPONSE";

    private final DisputeRepository disputes;
    private final DraftRepository drafts;
    private final DraftService draftService;
    private final boolean enabled;

    public AutoDraftListener(DisputeRepository disputes, DraftRepository drafts, DraftService draftService,
                             @Value("${dp.drafts.auto:true}") boolean enabled) {
        this.disputes = disputes;
        this.drafts = drafts;
        this.draftService = draftService;
        this.enabled = enabled;
    }

    @EventListener
    public void onDisputeChanged(DisputeChangedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            if (drafts.hasLiveDraft(event.disputeId())) {
                return;
            }
            boolean actionable = disputes.find(event.disputeId())
                    .filter(d -> WAITING_FOR_SELLER.equals(d.summary().status()))
                    .filter(d -> d.allowedActions().stream()
                            .anyMatch(a -> a.equalsIgnoreCase("PROVIDE_EVIDENCE")
                                    || a.equalsIgnoreCase("ACCEPT_CLAIM")))
                    .isPresent();
            if (!actionable) {
                return;
            }
            draftService.createDraft(event.disputeId());
        } catch (RuntimeException e) {
            // Auto-drafting is a convenience; a failure must never break dispute ingestion.
            log.warn("Auto-draft for dispute {} failed: {}", event.disputeId(), e.getMessage());
        }
    }
}
