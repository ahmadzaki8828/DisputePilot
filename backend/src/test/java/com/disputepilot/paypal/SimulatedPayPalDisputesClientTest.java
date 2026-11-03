package com.disputepilot.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.EvidenceItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SimulatedPayPalDisputesClientTest {

    private SimulatedPayPalDisputesClient client;

    @BeforeEach
    void setUp() {
        client = new SimulatedPayPalDisputesClient();
    }

    @Test
    void servesTheSixDemoDisputes() {
        DisputePage page = client.listDisputes(DisputeQuery.all());
        assertThat(page.items()).hasSize(6);
        assertThat(page.items()).allSatisfy(d -> {
            assertThat(d.paypalDisputeId()).startsWith("DEMO-");
            assertThat(d.simulated()).isTrue();
            assertThat(d.status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
            assertThat(d.allowedActions()).contains("PROVIDE_EVIDENCE", "ACCEPT_CLAIM");
            assertThat(d.requestedEvidence()).isNotEmpty();
            assertThat(d.sellerResponseDueAt()).isAfter(Instant.now());
        });
        assertThat(page.items()).extracting(PayPalDispute::reason).containsExactlyInAnyOrder(
                "MERCHANDISE_OR_SERVICE_NOT_RECEIVED", "MERCHANDISE_OR_SERVICE_NOT_RECEIVED",
                "MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED", "UNAUTHORISED", "CREDIT_NOT_PROCESSED",
                "DUPLICATE_TRANSACTION");
    }

    @Test
    void provideEvidenceMovesToUnderReviewAndRemovesActions() {
        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("PROOF_OF_FULFILLMENT", null, "Delivered with signature"))));
        PayPalDispute d = client.getDispute("DEMO-D-1001");
        assertThat(d.status()).isEqualTo("UNDER_REVIEW");
        assertThat(d.allowedActions()).doesNotContain("PROVIDE_EVIDENCE", "ACCEPT_CLAIM");
        assertThatThrownBy(() -> client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("OTHER", null, "again")))))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.debugId()).isNotBlank();
                });
    }

    @Test
    void sandboxHelpersCycleThroughReviewAndAdjudication() {
        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("PROOF_OF_FULFILLMENT", null, "x"))));
        client.requireEvidence("DEMO-D-1001", "SELLER_EVIDENCE");
        assertThat(client.getDispute("DEMO-D-1001").status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("OTHER", null, "y"))));
        client.adjudicate("DEMO-D-1001", "SELLER_FAVOR");
        PayPalDispute d = client.getDispute("DEMO-D-1001");
        assertThat(d.status()).isEqualTo("RESOLVED");
        assertThat(d.outcome()).isEqualTo("RESOLVED_SELLER_FAVOUR");
    }

    @Test
    void acceptClaimResolvesInBuyersFavour() {
        client.acceptClaim("DEMO-D-1004", new AcceptClaimRequest("accepting", null));
        PayPalDispute d = client.getDispute("DEMO-D-1004");
        assertThat(d.status()).isEqualTo("RESOLVED");
        assertThat(d.outcome()).isEqualTo("RESOLVED_BUYER_FAVOUR");
    }

    @Test
    void makeOfferOnlyWhereTheLinkExists() {
        assertThatThrownBy(() -> client.makeOffer("DEMO-D-1004", new OfferRequest("offer", null, null)))
                .isInstanceOf(PayPalApiException.class);
        client.makeOffer("DEMO-D-1002", new OfferRequest("offer", null, null));
        assertThat(client.getDispute("DEMO-D-1002").status()).isEqualTo("WAITING_FOR_BUYER_RESPONSE");
    }

    @Test
    void unknownDisputeIs404() {
        assertThatThrownBy(() -> client.getDispute("DEMO-NOPE"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
    }
}
