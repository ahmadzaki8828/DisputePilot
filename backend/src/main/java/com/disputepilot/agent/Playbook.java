package com.disputepilot.agent;

import com.disputepilot.domain.DisputeReason;
import com.disputepilot.domain.Recommendation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic guidance per dispute reason (plan §8.2): which PayPal evidence types are allowed, what each
 * one must carry, and which way the case usually leans. The same playbook is given to the model as guidance
 * and used again by {@link DraftValidator}, so the model can never widen what is allowed.
 */
public record Playbook(
        DisputeReason reason,
        List<String> allowedEvidenceTypes,
        Recommendation defaultLeaning,
        String guidance) {

    public static final String PROOF_OF_FULFILLMENT = "PROOF_OF_FULFILLMENT";
    public static final String PROOF_OF_REFUND = "PROOF_OF_REFUND";
    public static final String OTHER = "OTHER";

    private static final Map<DisputeReason, Playbook> BY_REASON = byReason(
            new Playbook(DisputeReason.MERCHANDISE_OR_SERVICE_NOT_RECEIVED,
                    List.of(PROOF_OF_FULFILLMENT, PROOF_OF_REFUND, OTHER),
                    Recommendation.FIGHT,
                    "Fight only with a shipment that shows delivery; a signature is the strongest fact. "
                            + "If nothing shipped, accept. If the parcel is still in transit past its ETA, offer."),
            new Playbook(DisputeReason.MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED,
                    List.of(OTHER, PROOF_OF_REFUND),
                    Recommendation.OFFER,
                    "Fight with OTHER evidence only when the listing description matches what shipped. "
                            + "If the buyer shows a genuine defect or damage, offer a partial or full refund."),
            new Playbook(DisputeReason.UNAUTHORISED,
                    List.of(OTHER, PROOF_OF_FULFILLMENT),
                    Recommendation.ACCEPT,
                    "Unauthorised-transaction claims are decided on the card issuer's fraud evidence, not the "
                            + "seller's. Proof of delivery, signature included, does not answer 'I never "
                            + "authorised this payment'. Accept unless the buyer's own messages show they knew "
                            + "about the order - chasing it, arranging a return, asking to exchange it."),
            new Playbook(DisputeReason.CREDIT_NOT_PROCESSED,
                    List.of(PROOF_OF_REFUND, OTHER),
                    Recommendation.FIGHT,
                    "Fight with PROOF_OF_REFUND when a refund exists in the merchant's records. "
                            + "If no refund was issued, accept and refund."),
            new Playbook(DisputeReason.DUPLICATE_TRANSACTION,
                    List.of(PROOF_OF_REFUND, OTHER),
                    Recommendation.FIGHT,
                    "Fight with PROOF_OF_REFUND when the duplicate charge was already refunded, or with OTHER "
                            + "when the two charges are genuinely separate orders."),
            new Playbook(DisputeReason.INCORRECT_AMOUNT,
                    List.of(PROOF_OF_REFUND, OTHER),
                    Recommendation.OFFER,
                    "Compare the charged amount with the order total. If the charge was too high, refund the "
                            + "difference; if it matches the order, fight with OTHER and cite the order."),
            new Playbook(DisputeReason.OTHER,
                    List.of(OTHER, PROOF_OF_FULFILLMENT, PROOF_OF_REFUND),
                    Recommendation.NEEDS_INFO,
                    "The reason is not one of PayPal's standard seller-answerable reasons; say what is missing."));

    private static Map<DisputeReason, Playbook> byReason(Playbook... books) {
        Map<DisputeReason, Playbook> map = new LinkedHashMap<>();
        for (Playbook b : books) {
            map.put(b.reason(), b);
        }
        return Map.copyOf(map);
    }

    public static Playbook forReason(String paypalReason) {
        return forReason(DisputeReason.fromPaypal(paypalReason));
    }

    public static Playbook forReason(DisputeReason reason) {
        return BY_REASON.getOrDefault(reason, BY_REASON.get(DisputeReason.OTHER));
    }

    /**
     * The evidence types this draft may use: PayPal's own requested list when it is non-empty (narrowed to
     * what the playbook allows), otherwise the playbook's list.
     */
    public List<String> effectiveEvidenceTypes(List<String> requestedByPaypal) {
        if (requestedByPaypal == null || requestedByPaypal.isEmpty()) {
            return allowedEvidenceTypes;
        }
        List<String> narrowed = requestedByPaypal.stream().filter(allowedEvidenceTypes::contains).toList();
        return narrowed.isEmpty() ? requestedByPaypal : narrowed;
    }

    /** Fields an evidence entry of this type must carry, as human-readable requirements. */
    public static List<String> requiredFields(String evidenceType) {
        return switch (evidenceType == null ? "" : evidenceType) {
            case PROOF_OF_FULFILLMENT -> List.of("evidence_info.tracking_info[].carrier_name",
                    "evidence_info.tracking_info[].tracking_number");
            case PROOF_OF_REFUND -> List.of("evidence_info.refund_ids[].refund_id");
            default -> List.of("notes");
        };
    }
}
