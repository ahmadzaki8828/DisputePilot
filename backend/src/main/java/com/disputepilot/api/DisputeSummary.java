package com.disputepilot.api;

import com.disputepilot.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DisputeSummary(
        UUID id,
        String paypalDisputeId,
        boolean simulated,
        String reason,
        String reasonLabel,
        String status,
        String stage,
        Money amount,
        Instant sellerResponseDueAt,
        LatestDraft latestDraft) {

    public record LatestDraft(String recommendation, BigDecimal confidence, String status) {
    }
}
