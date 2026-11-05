package com.disputepilot.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Money;
import java.math.BigDecimal;
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
        client.makeOffer("DEMO-D-1002", new OfferRequest("offer", new Money("CAD", new BigDecimal("20.00")), null));
        assertThat(client.getDispute("DEMO-D-1002").status()).isEqualTo("WAITING_FOR_BUYER_RESPONSE");
        assertThat(client.getDispute("DEMO-D-1002").raw().path("offer").path("offer_type").asString())
                .isEqualTo("REFUND");
    }

    @Test
    void anOfferIsRefusedTheWayPayPalRefusesAnIncompleteOne() {
        Money twenty = new Money("CAD", new BigDecimal("20.00"));
        assertThatThrownBy(() -> client.makeOffer("DEMO-D-1003", new OfferRequest("offer", null, null)))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> client.makeOffer("DEMO-D-1003",
                new OfferRequest("offer", twenty, null, "STORE_CREDIT", null)))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> client.makeOffer("DEMO-D-1003",
                new OfferRequest("offer", twenty, null, "REFUND_WITH_RETURN", null)))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.issues())
                        .containsExactly("MISSING_REQUIRED_PARAMETER"));
        client.makeOffer("DEMO-D-1003", new OfferRequest("replace it", null, null, "REPLACEMENT_WITHOUT_REFUND", null));
        assertThat(client.getDispute("DEMO-D-1003").status()).isEqualTo("WAITING_FOR_BUYER_RESPONSE");
    }

    @Test
    void unknownDisputeIs404() {
        assertThatThrownBy(() -> client.getDispute("DEMO-NOPE"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
    }

    @Test
    void sendMessageIsAllowedUntilTheDisputeIsResolved() {
        client.sendMessage("DEMO-D-1001", "Tracking shows delivery on 18 Oct.");
        assertThat(client.getDispute("DEMO-D-1001").raw().path("messages").size()).isEqualTo(2);
        assertThat(client.getDispute("DEMO-D-1001").allowedActions()).contains("SEND_MESSAGE");

        client.acceptClaim("DEMO-D-1001", new AcceptClaimRequest("accepting", null));
        assertThat(client.getDispute("DEMO-D-1001").allowedActions()).doesNotContain("SEND_MESSAGE");
        assertThatThrownBy(() -> client.sendMessage("DEMO-D-1001", "one more"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.issues()).containsExactly("DISPUTE_ALREADY_RESOLVED");
                });
    }

    @Test
    void providedEvidenceIsRecordedAsSubmittedBySeller() {
        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("PROOF_OF_FULFILLMENT", new EvidenceItem.EvidenceInfo(
                        List.of(new EvidenceItem.TrackingInfo("OTHER", "Northline Courier", "NLC4417002931")),
                        null), "Delivered with signature"))));

        PayPalDispute d = client.getDispute("DEMO-D-1001");
        var submitted = d.raw().path("evidences").valueStream()
                .filter(e -> "SUBMITTED_BY_SELLER".equals(e.path("source").asString(null))).toList();
        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0).path("evidence_type").asString()).isEqualTo("PROOF_OF_FULFILLMENT");
        assertThat(submitted.get(0).path("evidence_info").path("tracking_info").path(0)
                .path("carrier_name_other").asString()).isEqualTo("Northline Courier");
        // What PayPal asked for is unchanged by what we submitted.
        assertThat(d.requestedEvidence()).containsExactly("PROOF_OF_FULFILLMENT");
    }

    @Test
    void anOfferAboveTheDisputedAmountIsRefusedTheWayPayPalRefusesIt() {
        assertThatThrownBy(() -> client.makeOffer("DEMO-D-1002",
                new OfferRequest("too much", new Money("CAD", new BigDecimal("99999.00")), null)))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.issues()).containsExactly("OFFER_AMOUNT_EXCEEDS_DISPUTE_AMOUNT");
                    assertThat(e.debugId()).isNotBlank();
                });
    }

    @Test
    void adjudicationNeedsAReviewableDisputeAndAKnownOutcome() {
        assertThatThrownBy(() -> client.adjudicate("DEMO-D-1001", "SELLER_FAVOR"))
                .isInstanceOfSatisfying(PayPalApiException.class,
                        e -> assertThat(e.issues()).containsExactly("DISPUTE_STATE_NOT_VALID_FOR_ACTION"));

        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("OTHER", null, "x"))));
        assertThatThrownBy(() -> client.adjudicate("DEMO-D-1001", "COIN_FLIP"))
                .isInstanceOfSatisfying(PayPalApiException.class,
                        e -> assertThat(e.httpStatus()).isEqualTo(400));
        assertThat(client.getDispute("DEMO-D-1001").status()).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void requireEvidenceOnlyWorksWhileUnderReview() {
        assertThatThrownBy(() -> client.requireEvidence("DEMO-D-1001", "SELLER_EVIDENCE"))
                .isInstanceOf(PayPalApiException.class);
        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("OTHER", null, "x"))));
        assertThatThrownBy(() -> client.requireEvidence("DEMO-D-1001", "BUYER_EVIDENCE"))
                .isInstanceOf(PayPalApiException.class);
        client.requireEvidence("DEMO-D-1001", "SELLER_EVIDENCE");
        assertThat(client.getDispute("DEMO-D-1001").allowedActions())
                .contains("PROVIDE_EVIDENCE", "ACCEPT_CLAIM");
    }

    @Test
    void listDisputesHonoursStatusAndUpdatedAfterFilters() {
        // The reconciliation cursor the poller would hold before anything changed.
        Instant cursor = client.listDisputes(DisputeQuery.all()).items().stream()
                .map(PayPalDispute::updateTime).max(Instant::compareTo).orElseThrow();
        assertThat(cursor).isBeforeOrEqualTo(Instant.now());

        client.provideEvidence("DEMO-D-1001", new EvidenceSubmission(List.of(
                new EvidenceItem("OTHER", null, "x"))));

        assertThat(client.listDisputes(new DisputeQuery("UNDER_REVIEW", null, null, null)).items())
                .extracting(PayPalDispute::paypalDisputeId).containsExactly("DEMO-D-1001");
        assertThat(client.listDisputes(new DisputeQuery(null, cursor, null, null)).items())
                .extracting(PayPalDispute::paypalDisputeId).containsExactly("DEMO-D-1001");
        assertThat(client.listDisputes(new DisputeQuery(null, Instant.now().plusSeconds(60), null, null)).items())
                .isEmpty();
    }

    @Test
    void aNewDisputeCanBeClonedFromAnyFixtureTemplate() {
        assertThat(client.templates()).hasSize(6).allMatch(t -> t.startsWith("DEMO-"));

        PayPalDispute created = client.createFromTemplate("DEMO-D-1003");

        assertThat(created.paypalDisputeId()).startsWith("DEMO-D-9").isNotEqualTo("DEMO-D-1003");
        assertThat(created.simulated()).isTrue();
        assertThat(created.reason()).isEqualTo("MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED");
        assertThat(created.status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
        assertThat(created.outcome()).isNull();
        assertThat(created.sellerResponseDueAt()).isAfter(Instant.now());
        assertThat(created.allowedActions()).contains("PROVIDE_EVIDENCE", "ACCEPT_CLAIM");
        assertThat(created.raw().path("links").path(1).path("href").asString())
                .contains(created.paypalDisputeId());

        // It is a real dispute to the simulator from now on, and the template is untouched.
        assertThat(client.getDispute(created.paypalDisputeId()).paypalDisputeId())
                .isEqualTo(created.paypalDisputeId());
        assertThat(client.listDisputes(DisputeQuery.all()).items()).hasSize(7);
        assertThat(client.getDispute("DEMO-D-1003").status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");

        assertThatThrownBy(() -> client.createFromTemplate("DEMO-NOPE"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
    }

    @Test
    void resetDiscardsCreatedDisputesAndInMemoryChanges() {
        client.createFromTemplate("DEMO-D-1001");
        client.acceptClaim("DEMO-D-1004", new AcceptClaimRequest("accepting", null));

        client.resetToFixtures();

        assertThat(client.listDisputes(DisputeQuery.all()).items()).hasSize(6);
        assertThat(client.getDispute("DEMO-D-1004").status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
    }
}
