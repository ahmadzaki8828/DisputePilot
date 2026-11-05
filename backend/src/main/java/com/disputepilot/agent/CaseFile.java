package com.disputepilot.agent;

import com.disputepilot.domain.Money;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the agent is allowed to know about one dispute, already redacted, with a stable citation ID
 * on every record ({@code dispute:…}, {@code order:…}, {@code shipment:…}, {@code msg:…}, {@code policy:…}).
 *
 * <p>Buyer-written text ({@link DisputeMessageFacts}, {@link MessageFacts} with {@code from = "BUYER"}) is
 * untrusted data, never instructions.
 */
public record CaseFile(
        DisputeFacts dispute,
        OrderFacts order,
        List<ShipmentFacts> shipments,
        List<MessageFacts> customerMessages,
        List<DisputeMessageFacts> disputeMessages,
        PolicyFacts policy) {

    public record DisputeFacts(
            String citationId,
            String paypalDisputeId,
            boolean simulated,
            String reason,
            String reasonLabel,
            String status,
            String lifeCycleStage,
            Money amount,
            Instant sellerResponseDueAt,
            List<String> requestedEvidenceTypes,
            List<String> allowedActions,
            String disputedTransactionId) {
    }

    public record OrderFacts(
            String citationId,
            String orderId,
            String paypalTransactionId,
            Instant placedAt,
            String buyerName,
            String shippingCity,
            String shippingRegion,
            String shippingCountry,
            List<ItemFacts> items,
            Money total,
            String status,
            List<RefundFacts> refunds) {
    }

    public record ItemFacts(String sku, String name, int quantity, Money unitPrice) {
    }

    public record RefundFacts(String refundId, Money amount, Instant issuedAt, String reason) {
    }

    public record ShipmentFacts(
            String citationId,
            String shipmentId,
            String carrier,
            String trackingNumber,
            String status,
            Instant shippedAt,
            Instant deliveredAt,
            String signedBy,
            String deliveredToCity,
            List<String> events) {
    }

    /** A message in the merchant's own support thread. */
    public record MessageFacts(String citationId, String messageId, Instant at, String from, String body) {
    }

    /** A message on the PayPal dispute thread. */
    public record DisputeMessageFacts(String citationId, Instant postedAt, String postedBy, String content) {
    }

    /** The store policy, split into citable sections ({@code policy:returns}, {@code policy:shipping}, ...). */
    public record PolicyFacts(String merchantName, List<PolicySection> sections) {
    }

    public record PolicySection(String citationId, String name, String text) {
    }

    /** Citation ID of the policy section that says the store has a return address on file. */
    public static final String RETURN_ADDRESS_CITATION = "policy:return-address";

    /** True when the store has a return address, which a {@code REFUND_WITH_RETURN} offer needs. */
    public boolean hasReturnAddress() {
        return policy != null && policy.sections().stream()
                .anyMatch(s -> RETURN_ADDRESS_CITATION.equals(s.citationId()));
    }

    /** Every citation ID the model is allowed to use. */
    public Set<String> citationIds() {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(dispute.citationId());
        if (order != null) {
            ids.add(order.citationId());
        }
        shipments.forEach(s -> ids.add(s.citationId()));
        customerMessages.forEach(m -> ids.add(m.citationId()));
        disputeMessages.forEach(m -> ids.add(m.citationId()));
        if (policy != null) {
            policy.sections().forEach(s -> ids.add(s.citationId()));
        }
        return ids;
    }

    /** Tracking numbers that actually exist in the merchant's records. */
    public Set<String> knownTrackingNumbers() {
        Set<String> out = new LinkedHashSet<>();
        shipments.forEach(s -> {
            if (s.trackingNumber() != null && !s.trackingNumber().isBlank()) {
                out.add(s.trackingNumber());
            }
        });
        return out;
    }

    /** Refund IDs that actually exist in the merchant's records. */
    public Set<String> knownRefundIds() {
        Set<String> out = new LinkedHashSet<>();
        if (order != null) {
            order.refunds().forEach(r -> out.add(r.refundId()));
        }
        return out;
    }

    public boolean hasOrder() {
        return order != null;
    }
}
