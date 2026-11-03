package com.disputepilot.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A dispute row as stored in the disputes table. */
public record Dispute(
        UUID id,
        String paypalDisputeId,
        boolean simulated,
        String reason,
        String status,
        String lifeCycleStage,
        Money amount,
        Instant sellerResponseDueAt,
        String disputedTransactionId,
        List<String> requestedEvidence,
        List<String> allowedActions,
        String outcome,
        Instant paypalUpdateTime) {
}
