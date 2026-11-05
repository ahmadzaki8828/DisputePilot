package com.disputepilot.drafts;

import com.disputepilot.api.DisputeRepository;
import com.disputepilot.domain.DisputeChangedEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private final ExecutorService executor;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    /**
     * @param async when true (the default) the draft runs on a background thread after the ingesting
     *              transaction commits, so a slow live model never delays webhook processing or holds its
     *              database connection; tests set {@code dp.drafts.async=false} to draft inline.
     */
    public AutoDraftListener(DisputeRepository disputes, DraftRepository drafts, DraftService draftService,
                             @Value("${dp.drafts.auto:true}") boolean enabled,
                             @Value("${dp.drafts.async:true}") boolean async) {
        this.disputes = disputes;
        this.drafts = drafts;
        this.draftService = draftService;
        this.enabled = enabled;
        this.executor = async ? Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "auto-draft");
            t.setDaemon(true);
            return t;
        }) : null;
    }

    @EventListener
    public void onDisputeChanged(DisputeChangedEvent event) {
        if (!enabled) {
            return;
        }
        if (executor == null) {
            handle(event);
            return;
        }
        Runnable dispatch = () -> executor.execute(() -> handle(event));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // The dispute row must be committed before another thread reads it.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            dispatch.run();
        }
    }

    private void handle(DisputeChangedEvent event) {
        if (!inFlight.add(event.disputeId())) {
            return; // a draft for this dispute is already being written
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
        } finally {
            inFlight.remove(event.disputeId());
        }
    }
}
