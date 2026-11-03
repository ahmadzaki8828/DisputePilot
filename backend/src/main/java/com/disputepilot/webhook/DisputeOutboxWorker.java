package com.disputepilot.webhook;

import com.disputepilot.paypal.PayPalApiException;
import com.disputepilot.sync.DisputeSyncService;
import com.disputepilot.sync.SyncProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code webhook_events}: for each stored delivery, re-fetch the dispute from PayPal and upsert it
 * (plan §13 A.5). This is the "exactly-once effect" half of plan §3 - PayPal may deliver an event many times,
 * but the row is unique by event ID and the refresh is idempotent.
 *
 * <p>Events are claimed with {@code for update skip locked} so several instances can run at once, each attempt
 * is counted, a failure records {@code last_error} and backs the event off, and an event that has used up its
 * attempts is left alone for an operator to look at.
 */
@Component
public class DisputeOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(DisputeOutboxWorker.class);

    private final WebhookEventRepository events;
    private final DisputeSyncService sync;
    private final SyncProperties props;

    public DisputeOutboxWorker(WebhookEventRepository events, DisputeSyncService sync, SyncProperties props) {
        this.events = events;
        this.sync = sync;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${dp.sync.outbox-interval:PT5S}", initialDelayString = "PT5S")
    void scheduled() {
        if (!props.schedulingEnabled()) {
            return;
        }
        try {
            processPending();
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled, so nothing may escape here.
            log.error("Outbox worker run failed", e);
        }
    }

    /**
     * Process one batch.
     *
     * @return how many events were processed successfully
     */
    public int processPending() {
        List<WebhookEvent> batch = events.claimBatch(props.outboxBatchSize(), props.maxAttempts());
        int done = 0;
        for (WebhookEvent event : batch) {
            if (process(event)) {
                done++;
            }
        }
        if (!batch.isEmpty()) {
            log.info("Outbox processed {}/{} claimed webhook events", done, batch.size());
        }
        return done;
    }

    private boolean process(WebhookEvent event) {
        if (event.resourceId() == null || event.resourceId().isBlank()) {
            // Not about a dispute we can fetch; nothing to do, and retrying will not help.
            events.markProcessed(event.id());
            log.info("Webhook {} ({}) carries no dispute id; marked processed", event.paypalEventId(),
                    event.eventType());
            return true;
        }
        try {
            sync.sync(event.resourceId(), event.eventType());
            events.markProcessed(event.id());
            return true;
        } catch (PayPalApiException e) {
            String error = "PayPal " + e.httpStatus() + " " + e.name() + " debug_id=" + e.debugId();
            events.markFailed(event.id(), error);
            log.warn("Webhook {} attempt {}/{} failed: {}", event.paypalEventId(), event.attempts(),
                    props.maxAttempts(), error);
            return false;
        } catch (RuntimeException e) {
            events.markFailed(event.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Webhook {} attempt {}/{} failed", event.paypalEventId(), event.attempts(),
                    props.maxAttempts(), e);
            return false;
        }
    }
}
