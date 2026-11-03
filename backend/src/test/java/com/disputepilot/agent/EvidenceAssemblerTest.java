package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.merchant.SeededMerchantData;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EvidenceAssemblerTest {

    private static EvidenceAssembler assembler;
    private static SimulatedPayPalDisputesClient paypal;

    @BeforeAll
    static void setUp() {
        paypal = new SimulatedPayPalDisputesClient();
        assembler = new EvidenceAssembler(new SeededMerchantData());
    }

    private static PayPalDispute dispute(String id) {
        return paypal.listDisputes(DisputeQuery.all()).items().stream()
                .filter(d -> d.paypalDisputeId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyRecordGetsAStableCitationId() {
        CaseFile c = assembler.assemble(dispute("DEMO-D-1001")).caseFile();
        assertThat(c.dispute().citationId()).isEqualTo("dispute:DEMO-D-1001");
        assertThat(c.order().citationId()).isEqualTo("order:ORD-1042");
        assertThat(c.shipments()).extracting(CaseFile.ShipmentFacts::citationId).containsExactly("shipment:SHP-77");
        assertThat(c.customerMessages()).extracting(CaseFile.MessageFacts::citationId)
                .containsExactly("msg:MSG-1", "msg:MSG-2");
        assertThat(c.disputeMessages()).extracting(CaseFile.DisputeMessageFacts::citationId).containsExactly("msg:DM-1");
        assertThat(c.citationIds()).contains("policy:returns", "policy:shipping");
    }

    @Test
    void knownFactsAreCollectedForTheValidator() {
        CaseFile c = assembler.assemble(dispute("DEMO-D-1001")).caseFile();
        assertThat(c.knownTrackingNumbers()).containsExactly("NLC4417002931");
        assertThat(c.knownRefundIds()).isEmpty();

        CaseFile refunded = assembler.assemble(dispute("DEMO-D-1005")).caseFile();
        assertThat(refunded.knownRefundIds()).containsExactly("REF-5001");
    }

    @Test
    void theCaseFileCarriesPaypalsRequestedEvidenceAndAllowedActions() {
        CaseFile c = assembler.assemble(dispute("DEMO-D-1001")).caseFile();
        assertThat(c.dispute().requestedEvidenceTypes()).containsExactly("PROOF_OF_FULFILLMENT");
        assertThat(c.dispute().allowedActions()).contains("PROVIDE_EVIDENCE", "ACCEPT_CLAIM");
        assertThat(c.dispute().reasonLabel()).isEqualTo("Item not received");
    }

    @Test
    void buyerPiiNeverReachesTheCaseFile() {
        EvidenceAssembler.Assembled a = assembler.assemble(dispute("DEMO-D-1001"));
        String rendered = a.caseFile().toString();
        assertThat(rendered).doesNotContain("jordan.ellis@buyer.example", "416-555-0101", "1201 Example Street");
        assertThat(a.caseFile().order().shippingCity()).isEqualTo("Toronto");
        assertThat(a.redactor().containsPii(rendered)).isFalse();
    }

    @Test
    void aDisputeWithNoMatchingOrderStillProducesACaseFile() {
        PayPalDispute orphan = new PayPalDispute("DEMO-D-9999", true, "MERCHANDISE_OR_SERVICE_NOT_RECEIVED",
                "WAITING_FOR_SELLER_RESPONSE", "INQUIRY", dispute("DEMO-D-1001").amount(), null,
                "TXN-DOES-NOT-EXIST", java.util.List.of("PROOF_OF_FULFILLMENT"), java.util.List.of("ACCEPT_CLAIM"),
                null, null, null);
        CaseFile c = assembler.assemble(orphan).caseFile();
        assertThat(c.hasOrder()).isFalse();
        assertThat(c.shipments()).isEmpty();
        assertThat(c.citationIds()).contains("dispute:DEMO-D-9999");
    }
}
