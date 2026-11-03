package com.disputepilot.paypal;

import com.disputepilot.domain.Money;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Dispute details as returned by PayPal: mapped domain fields plus the raw JSON payload. */
public record PayPalDispute(
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
        Instant updateTime,
        JsonNode raw) {
}
