package com.disputepilot.submission;

import com.disputepilot.domain.SubmissionAction;
import java.time.Instant;
import java.util.UUID;

/** One attempt to act on a dispute at PayPal, guarded by its idempotency key. Mirrors the contract's Submission. */
public record Submission(
        UUID id,
        UUID draftId,
        String idempotencyKey,
        SubmissionAction action,
        Status status,
        String paypalDebugId,
        Instant createdAt) {

    public enum Status { PENDING, SUCCEEDED, FAILED }
}
