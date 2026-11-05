package com.disputepilot.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mirrors the drafts table. */
public record Draft(
        UUID id,
        UUID disputeId,
        int version,
        DraftStatus status,
        Recommendation recommendation,
        BigDecimal confidence,
        String rationale,
        List<EvidenceItem> evidence,
        String notes,
        List<Citation> citations,
        List<String> missingInfo,
        Offer offer,
        ValidationResult validation,
        LlmMode llmMode,
        String provider,
        String model,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens,
        Integer latencyMs,
        boolean editedByHuman,
        boolean repairAttempted,
        Instant createdAt) {
}
