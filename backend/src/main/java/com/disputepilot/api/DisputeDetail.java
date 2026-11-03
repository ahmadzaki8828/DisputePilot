package com.disputepilot.api;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.Money;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DisputeDetail(
        UUID id,
        String paypalDisputeId,
        boolean simulated,
        String reason,
        String reasonLabel,
        String status,
        String stage,
        Money amount,
        Instant sellerResponseDueAt,
        DisputeSummary.LatestDraft latestDraft,
        List<String> requestedEvidence,
        List<String> allowedActions,
        List<DisputeMessage> messages,
        CaseFile caseFile,
        String disputedTransactionId) {

    /** A message on the PayPal dispute thread (buyer text is untrusted). */
    public record DisputeMessage(String postedBy, Instant postedAt, String content) {
    }

    public record CaseFile(
            Order order,
            List<Shipment> shipments,
            List<CustomerMessage> customerMessages,
            StorePolicy policy) {
    }
}
