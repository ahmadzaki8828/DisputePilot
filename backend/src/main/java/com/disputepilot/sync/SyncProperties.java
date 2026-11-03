package com.disputepilot.sync;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timings for the webhook outbox worker and the reconciliation poller (plan §8.3). Defaults are the values in
 * the plan: a fast outbox loop, at most 5 attempts per event, and reconciliation every 10 minutes.
 */
@ConfigurationProperties("dp.sync")
public record SyncProperties(
        /** Whether the scheduled outbox worker and poller run. Tests drive them directly instead. */
        @DefaultValue("true") boolean schedulingEnabled,
        @DefaultValue("PT5S") Duration outboxInterval,
        @DefaultValue("20") int outboxBatchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT10M") Duration pollInterval,
        @DefaultValue("50") int pollPageSize,
        /** Safety stop so one reconciliation run cannot page through PayPal forever. */
        @DefaultValue("10") int pollMaxPages,
        /** How far back the first reconciliation run looks when no cursor has been stored yet. */
        @DefaultValue("P7D") Duration pollLookback) {
}
