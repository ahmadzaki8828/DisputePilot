package com.disputepilot.webhook;

import java.time.Instant;
import java.util.UUID;

/** A row of {@code webhook_events} as the outbox worker needs it. */
public record WebhookEvent(
        UUID id,
        String paypalEventId,
        String eventType,
        String resourceId,
        String verification,
        Instant receivedAt,
        int attempts) {

    /** Verification outcomes, mirroring the {@code webhook_events.verification} column. */
    public static final String VERIFIED = "VERIFIED";
    public static final String SKIPPED_SIMULATED = "SKIPPED_SIMULATED";
    public static final String FAILED = "FAILED";
}
