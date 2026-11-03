package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.domain.Citation;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import com.disputepilot.merchant.SeededMerchantData;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import java.math.BigDecimal;
import java.util.List;
import org.assertj.core.api.ListAssert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** One test per validator rule (plan §9.2 step 5). */
class DraftValidatorTest {

    private static EvidenceAssembler assembler;
    private static SimulatedPayPalDisputesClient paypal;
    private final DraftValidator validator = new DraftValidator();

    @BeforeAll
    static void setUp() {
        paypal = new SimulatedPayPalDisputesClient();
        assembler = new EvidenceAssembler(new SeededMerchantData());
    }

    private static EvidenceAssembler.Assembled assembled(String disputeId) {
        return assembler.assemble(paypal.listDisputes(DisputeQuery.all()).items().stream()
                .filter(d -> d.paypalDisputeId().equals(disputeId)).findFirst().orElseThrow());
    }

    private ListAssert<String> codes(DraftProposal draft, String disputeId) {
        EvidenceAssembler.Assembled a = assembled(disputeId);
        ValidationResult result = validator.validate(draft, a.caseFile(),
                Playbook.forReason(a.caseFile().dispute().reason()), a.redactor());
        return assertThat(result.issues().stream().map(ValidationResult.Issue::code).toList());
    }

    private static EvidenceItem fulfilment(String trackingNumber) {
        return new EvidenceItem("PROOF_OF_FULFILLMENT",
                new EvidenceItem.EvidenceInfo(
                        List.of(new EvidenceItem.TrackingInfo("OTHER", "Northline Courier", trackingNumber)), null),
                "Delivered and signed for.");
    }

    private static DraftProposal fight(List<EvidenceItem> evidence, List<Citation> citations) {
        return new DraftProposal(Recommendation.FIGHT, new BigDecimal("0.93"),
                "Delivered with a signature before the dispute was opened.", evidence,
                "The order was delivered by Northline Courier and signed for on 2026-10-21.",
                citations, List.of(), null);
    }

    private static List<Citation> goodCitations() {
        return List.of(new Citation("order:ORD-1042", "order", "Order ORD-1042"),
                new Citation("shipment:SHP-77", "shipment", "Shipment SHP-77"));
    }

    @Test
    void aGroundedFightDraftPasses() {
        codes(fight(List.of(fulfilment("NLC4417002931")), goodCitations()), "DEMO-D-1001").isEmpty();
    }

    @Test
    void anEvidenceTypeTheReasonDoesNotAllowFails() {
        // DEMO-D-1003 is MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED; fulfilment proof is not allowed for it.
        codes(fight(List.of(fulfilment("NLC4417002944")),
                List.of(new Citation("order:ORD-1044", "order", "Order ORD-1044"))), "DEMO-D-1003")
                .contains(DraftValidator.EVIDENCE_TYPE_NOT_ALLOWED);
    }

    @Test
    void anEvidenceTypePaypalDidNotAskForFails() {
        // DEMO-D-1001 requests only PROOF_OF_FULFILLMENT; OTHER is allowed by the reason but not requested.
        EvidenceItem other = new EvidenceItem("OTHER", null, "Here is the listing description.");
        codes(fight(List.of(other), goodCitations()), "DEMO-D-1001")
                .contains(DraftValidator.EVIDENCE_TYPE_NOT_REQUESTED);
    }

    @Test
    void aTrackingNumberThatIsNotInTheRecordsFails() {
        codes(fight(List.of(fulfilment("NLC-FAKE-0001")), goodCitations()), "DEMO-D-1001")
                .contains(DraftValidator.UNKNOWN_TRACKING_NUMBER);
    }

    @Test
    void aRefundIdThatIsNotInTheRecordsFails() {
        EvidenceItem refund = new EvidenceItem("PROOF_OF_REFUND",
                new EvidenceItem.EvidenceInfo(null, List.of(new EvidenceItem.RefundId("REF-9999"))), null);
        codes(fight(List.of(refund), List.of(new Citation("order:ORD-1046", "order", "Order ORD-1046"))),
                "DEMO-D-1005").contains(DraftValidator.UNKNOWN_REFUND_ID);
    }

    @Test
    void theRealRefundIdPasses() {
        EvidenceItem refund = new EvidenceItem("PROOF_OF_REFUND",
                new EvidenceItem.EvidenceInfo(null, List.of(new EvidenceItem.RefundId("REF-5001"))),
                "Refunded in full on 2026-10-20.");
        codes(fight(List.of(refund), List.of(new Citation("order:ORD-1046", "order", "Order ORD-1046"))),
                "DEMO-D-1005").isEmpty();
    }

    @Test
    void aCitationIdThatDoesNotExistFails() {
        codes(fight(List.of(fulfilment("NLC4417002931")),
                List.of(new Citation("shipment:SHP-999", "shipment", "Invented"))), "DEMO-D-1001")
                .contains(DraftValidator.UNKNOWN_CITATION);
    }

    @Test
    void anArguingDraftWithNoCitationsFails() {
        codes(fight(List.of(fulfilment("NLC4417002931")), List.of()), "DEMO-D-1001")
                .contains(DraftValidator.NO_CITATIONS);
    }

    @Test
    void evidenceMissingItsRequiredFieldsFails() {
        EvidenceItem noTracking = new EvidenceItem("PROOF_OF_FULFILLMENT", null, "It definitely shipped.");
        codes(fight(List.of(noTracking), goodCitations()), "DEMO-D-1001")
                .contains(DraftValidator.EVIDENCE_MISSING_REQUIRED_FIELD);
    }

    @Test
    void aLeftoverRedactionTokenInTheNoteFails() {
        DraftProposal d = new DraftProposal(Recommendation.FIGHT, new BigDecimal("0.9"), "Delivered.",
                List.of(fulfilment("NLC4417002931")),
                "We emailed the buyer at [BUYER_EMAIL] with the tracking number.", goodCitations(),
                List.of(), null);
        codes(d, "DEMO-D-1001").contains(DraftValidator.REDACTION_TOKEN_IN_TEXT);
    }

    @Test
    void rawBuyerPiiInTheNoteFails() {
        DraftProposal d = new DraftProposal(Recommendation.FIGHT, new BigDecimal("0.9"), "Delivered.",
                List.of(fulfilment("NLC4417002931")),
                "Delivered to 1201 Example Street and confirmed by jordan.ellis@buyer.example.",
                goodCitations(), List.of(), null);
        codes(d, "DEMO-D-1001").contains(DraftValidator.PII_IN_TEXT);
    }

    @Test
    void anOfferAboveTheDisputedAmountFails() {
        DraftProposal d = new DraftProposal(Recommendation.OFFER, new BigDecimal("0.6"), "Settle it.",
                List.of(), "We will refund the buyer in full.",
                List.of(new Citation("order:ORD-1044", "order", "Order ORD-1044")), List.of(),
                new BigDecimal("500.00"));
        codes(d, "DEMO-D-1003").contains(DraftValidator.OFFER_EXCEEDS_DISPUTED_AMOUNT);
    }

    @Test
    void anOfferWithNoAmountFails() {
        DraftProposal d = new DraftProposal(Recommendation.OFFER, new BigDecimal("0.6"), "Settle it.",
                List.of(), "We will refund part of the order.",
                List.of(new Citation("order:ORD-1044", "order", "Order ORD-1044")), List.of(), null);
        codes(d, "DEMO-D-1003").contains(DraftValidator.OFFER_WITHOUT_AMOUNT);
    }

    @Test
    void anOfferWithinTheDisputedAmountPasses() {
        DraftProposal d = new DraftProposal(Recommendation.OFFER, new BigDecimal("0.6"),
                "A partial refund settles a colour-variation claim.", List.of(),
                "We offer a partial refund of CAD 40.00 for the colour variation.",
                List.of(new Citation("policy:descriptions", "policy", "Product descriptions")), List.of(),
                new BigDecimal("40.00"));
        codes(d, "DEMO-D-1003").isEmpty();
    }

    @Test
    void fightWithNoEvidenceFails() {
        codes(fight(List.of(), goodCitations()), "DEMO-D-1001").contains(DraftValidator.FIGHT_WITHOUT_EVIDENCE);
    }

    @Test
    void acceptNeedsNeitherEvidenceNorCitations() {
        DraftProposal d = new DraftProposal(Recommendation.ACCEPT, new BigDecimal("0.8"),
                "Unauthorised claims are decided on the issuer's evidence; fighting wastes the merchant's time.",
                List.of(), "", List.of(), List.of(), null);
        codes(d, "DEMO-D-1004").isEmpty();
    }

    @Test
    void overLongRationaleAndNotesFail() {
        DraftProposal d = new DraftProposal(Recommendation.FIGHT, new BigDecimal("0.9"),
                "x".repeat(DraftValidator.MAX_RATIONALE + 1), List.of(fulfilment("NLC4417002931")),
                "y".repeat(DraftValidator.MAX_NOTES + 1), goodCitations(), List.of(), null);
        codes(d, "DEMO-D-1001")
                .contains(DraftValidator.RATIONALE_TOO_LONG, DraftValidator.NOTES_TOO_LONG);
    }

    @Test
    void confidenceOutsideZeroToOneFails() {
        DraftProposal d = fight(List.of(fulfilment("NLC4417002931")), goodCitations());
        DraftProposal bad = new DraftProposal(d.recommendation(), new BigDecimal("1.4"), d.rationale(),
                d.evidence(), d.notes(), d.citations(), d.missingInfo(), null);
        codes(bad, "DEMO-D-1001").contains(DraftValidator.CONFIDENCE_OUT_OF_RANGE);
    }

    @Test
    void aMissingDraftIsAFailureNotAnException() {
        EvidenceAssembler.Assembled a = assembled("DEMO-D-1001");
        ValidationResult result = validator.validate(null, a.caseFile(),
                Playbook.forReason(a.caseFile().dispute().reason()), a.redactor());
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).hasSize(1);
    }
}
