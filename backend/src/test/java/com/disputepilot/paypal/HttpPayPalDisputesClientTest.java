package com.disputepilot.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Money;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The real PayPal client against WireMock: no test in this class touches the network. */
class HttpPayPalDisputesClientTest {

    private static final String DISPUTE = "/v1/customer/disputes/PP-D-77001";

    private WireMockServer server;
    private HttpPayPalDisputesClient client;

    @BeforeEach
    void setUp() {
        server = PayPalWireMockSupport.start();
        PayPalWireMockSupport.stubToken(server, "token-1", 3600);
        client = PayPalWireMockSupport.client(server);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void getDisputeMapsStatusAmountEvidenceAndAllowedActions() {
        server.stubFor(get(urlPathEqualTo(DISPUTE))
                .willReturn(okJson(PayPalWireMockSupport.disputeJson("PP-D-77001", "WAITING_FOR_SELLER_RESPONSE"))));

        PayPalDispute d = client.getDispute("PP-D-77001");

        assertThat(d.paypalDisputeId()).isEqualTo("PP-D-77001");
        assertThat(d.simulated()).isFalse();
        assertThat(d.reason()).isEqualTo("MERCHANDISE_OR_SERVICE_NOT_RECEIVED");
        assertThat(d.status()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
        assertThat(d.lifeCycleStage()).isEqualTo("CHARGEBACK");
        assertThat(d.amount()).isEqualTo(new Money("CAD", new BigDecimal("180.00")));
        assertThat(d.sellerResponseDueAt()).isEqualTo(Instant.parse("2026-11-20T23:59:59Z"));
        assertThat(d.updateTime()).isEqualTo(Instant.parse("2026-10-21T11:30:00Z"));
        assertThat(d.disputedTransactionId()).isEqualTo("TXN-8F3A1001");
        // Only evidence PayPal asked the seller for, not what the seller already submitted.
        assertThat(d.requestedEvidence()).containsExactly("PROOF_OF_FULFILLMENT");
        assertThat(d.allowedActions()).containsExactly("PROVIDE_EVIDENCE", "ACCEPT_CLAIM", "SEND_MESSAGE");
        assertThat(d.outcome()).isNull();
        // The raw payload is kept verbatim for the disputes.raw column.
        assertThat(d.raw().path("dispute_channel").asString(null)).isNull();
        assertThat(d.raw().path("messages").path(0).path("posted_by").asString()).isEqualTo("BUYER");
        server.verify(getRequestedFor(urlPathEqualTo(DISPUTE))
                .withHeader("Authorization", equalTo("Bearer token-1")));
    }

    @Test
    void listDisputesPassesFiltersAndReadsTheNextPageToken() {
        server.stubFor(get(urlPathEqualTo("/v1/customer/disputes")).willReturn(okJson("""
                {"items":[%s],
                 "links":[{"rel":"next","href":"https://api-m.sandbox.paypal.com/v1/customer/disputes?\
page_size=2&next_page_token=TOK-42","method":"GET"}]}"""
                .formatted(PayPalWireMockSupport.disputeJson("PP-D-77001", "UNDER_REVIEW")))));

        DisputePage page = client.listDisputes(new DisputeQuery("REQUIRED_ACTION",
                Instant.parse("2026-10-01T00:00:00Z"), 2, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("UNDER_REVIEW");
        assertThat(page.nextPageToken()).isEqualTo("TOK-42");
        server.verify(getRequestedFor(urlPathEqualTo("/v1/customer/disputes"))
                .withQueryParam("dispute_state", equalTo("REQUIRED_ACTION"))
                .withQueryParam("update_time_after", equalTo("2026-10-01T00:00:00Z"))
                .withQueryParam("page_size", equalTo("2")));
    }

    @Test
    void provideEvidenceSendsMultipartWithAJsonInputPart() {
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/provide-evidence"))
                .willReturn(okJson("{\"links\":[]}")));

        client.provideEvidence("PP-D-77001", new EvidenceSubmission(
                List.of(new EvidenceItem("PROOF_OF_FULFILLMENT",
                        new EvidenceItem.EvidenceInfo(
                                List.of(new EvidenceItem.TrackingInfo("OTHER", "Northline Courier",
                                        "NLC4417002931")),
                                null),
                        "Delivered 18 Oct, signed for by the buyer.")),
                List.of(new EvidenceSubmission.FilePart("proof-of-delivery.txt", "text/plain",
                        "signed by J. Ellis".getBytes(StandardCharsets.UTF_8)))));

        List<LoggedRequest> requests = server.findAll(
                postRequestedFor(urlPathEqualTo(DISPUTE + "/provide-evidence")));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        String body = request.getBodyAsString();
        assertThat(body).contains("name=\"input\"");
        assertThat(body).contains("Content-Type: application/json");
        assertThat(body).contains("\"evidences\"");
        assertThat(body).contains("\"evidence_type\":\"PROOF_OF_FULFILLMENT\"");
        assertThat(body).contains("\"tracking_info\"");
        // Fictional carrier: PayPal gets carrier_name OTHER plus the free-text name.
        assertThat(body).contains("\"carrier_name\":\"OTHER\"");
        assertThat(body).contains("\"carrier_name_other\":\"Northline Courier\"");
        assertThat(body).contains("\"tracking_number\":\"NLC4417002931\"");
        assertThat(body).contains("name=\"file1\"");
        assertThat(body).contains("filename=\"proof-of-delivery.txt\"");
        assertThat(body).contains("signed by J. Ellis");
    }

    @Test
    void provideEvidenceWithoutEvidenceIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> client.provideEvidence("PP-D-77001", new EvidenceSubmission(List.of())))
                .isInstanceOfSatisfying(PayPalApiException.class,
                        e -> assertThat(e.httpStatus()).isEqualTo(400));
        server.verify(0, postRequestedFor(urlPathEqualTo(DISPUTE + "/provide-evidence")));
    }

    @Test
    void acceptClaimAndOfferAndMessageSendPayPalShapedBodies() {
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/accept-claim")).willReturn(okJson("{}")));
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/make-offer")).willReturn(okJson("{}")));
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/send-message")).willReturn(okJson("{}")));
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/require-evidence")).willReturn(okJson("{}")));
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/adjudicate")).willReturn(okJson("{}")));

        client.acceptClaim("PP-D-77001", new AcceptClaimRequest("Refunding in full.", "COMPANY_POLICY"));
        client.makeOffer("PP-D-77001", new OfferRequest("Partial refund offered.",
                new Money("CAD", new BigDecimal("90.00")), "INV-1042"));
        client.sendMessage("PP-D-77001", "Tracking shows delivery on 18 Oct.");
        client.requireEvidence("PP-D-77001", "SELLER_EVIDENCE");
        client.adjudicate("PP-D-77001", "SELLER_FAVOR");

        server.verify(postRequestedFor(urlPathEqualTo(DISPUTE + "/accept-claim")).withRequestBody(equalToJson(
                "{\"note\":\"Refunding in full.\",\"accept_claim_reason\":\"COMPANY_POLICY\"}")));
        server.verify(postRequestedFor(urlPathEqualTo(DISPUTE + "/make-offer")).withRequestBody(equalToJson(
                """
                {"note":"Partial refund offered.","offer_amount":{"currency_code":"CAD","value":"90.00"},
                 "invoice_id":"INV-1042","offer_type":"REFUND"}""")));
        server.verify(postRequestedFor(urlPathEqualTo(DISPUTE + "/send-message")).withRequestBody(equalToJson(
                "{\"message\":\"Tracking shows delivery on 18 Oct.\"}")));
        server.verify(postRequestedFor(urlPathEqualTo(DISPUTE + "/require-evidence")).withRequestBody(equalToJson(
                "{\"action\":\"SELLER_EVIDENCE\"}")));
        server.verify(postRequestedFor(urlPathEqualTo(DISPUTE + "/adjudicate")).withRequestBody(equalToJson(
                "{\"adjudication_outcome\":\"SELLER_FAVOR\"}")));
    }

    @Test
    void errorResponseIsMappedWithNameDebugIdAndIssues() {
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/provide-evidence"))
                .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json")
                        .withBody(PayPalWireMockSupport.errorJson("UNPROCESSABLE_ENTITY",
                                "The requested action could not be performed.", "d4e5f6a7b8c9",
                                "DISPUTE_STATE_NOT_VALID_FOR_ACTION"))));

        assertThatThrownBy(() -> client.provideEvidence("PP-D-77001", new EvidenceSubmission(
                List.of(new EvidenceItem("OTHER", null, "notes")))))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(422);
                    assertThat(e.name()).isEqualTo("UNPROCESSABLE_ENTITY");
                    assertThat(e.debugId()).isEqualTo("d4e5f6a7b8c9");
                    assertThat(e.issues()).containsExactly("DISPUTE_STATE_NOT_VALID_FOR_ACTION");
                    assertThat(e.getMessage()).isEqualTo("The requested action could not be performed.");
                    assertThat(e.retryable()).isFalse();
                });
        server.verify(1, postRequestedFor(urlPathEqualTo(DISPUTE + "/provide-evidence")));
    }

    @Test
    void anEmptyErrorBodyStillCarriesTheHttpStatus() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.getDispute("PP-D-77001"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(403);
                    assertThat(e.name()).isEqualTo("PAYPAL_HTTP_403");
                    assertThat(e.debugId()).isNull();
                });
    }

    @Test
    void getRetriesRateLimitingUntilItSucceeds() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).inScenario("429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withBody(PayPalWireMockSupport.errorJson("RATE_LIMIT_REACHED", "Too many requests.",
                                "rate-1", "RATE_LIMIT_REACHED")))
                .willSetStateTo("ok"));
        server.stubFor(get(urlPathEqualTo(DISPUTE)).inScenario("429").whenScenarioStateIs("ok")
                .willReturn(okJson(PayPalWireMockSupport.disputeJson("PP-D-77001", "UNDER_REVIEW"))));

        assertThat(client.getDispute("PP-D-77001").status()).isEqualTo("UNDER_REVIEW");
        server.verify(2, getRequestedFor(urlPathEqualTo(DISPUTE)));
    }

    @Test
    void getRetriesServerErrorsUpToTheAttemptLimit() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody(PayPalWireMockSupport.errorJson("INTERNAL_SERVER_ERROR", "Try again later.",
                        "srv-9", "INTERNAL_ERROR"))));

        assertThatThrownBy(() -> client.getDispute("PP-D-77001"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.debugId()).isEqualTo("srv-9");
                });
        server.verify(3, getRequestedFor(urlPathEqualTo(DISPUTE)));
    }

    @Test
    void clientErrorsAreNotRetried() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody(PayPalWireMockSupport.errorJson("RESOURCE_NOT_FOUND", "The resource does not exist.",
                        "nf-1", "INVALID_RESOURCE_ID"))));

        assertThatThrownBy(() -> client.getDispute("PP-D-77001"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        server.verify(1, getRequestedFor(urlPathEqualTo(DISPUTE)));
    }

    @Test
    void aPostIsRetriedOnlyWhenPayPalProvesItDidNotApply() {
        // 429: PayPal rejected the request outright, so re-sending is safe.
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/accept-claim")).inScenario("throttled")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)).willSetStateTo("ok"));
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/accept-claim")).inScenario("throttled")
                .whenScenarioStateIs("ok").willReturn(okJson("{}")));

        client.acceptClaim("PP-D-77001", new AcceptClaimRequest("note", null));
        server.verify(2, postRequestedFor(urlPathEqualTo(DISPUTE + "/accept-claim")));

        // 500: the action may well have been applied, so plan §8.3 forbids repeating it blindly.
        server.stubFor(post(urlPathEqualTo(DISPUTE + "/send-message")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.sendMessage("PP-D-77001", "hello"))
                .isInstanceOf(PayPalApiException.class);
        server.verify(1, postRequestedFor(urlPathEqualTo(DISPUTE + "/send-message")));
    }

    @Test
    void aStaleTokenIsRefreshedOnceAfterA401() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).inScenario("401")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401)).willSetStateTo("authorised"));
        server.stubFor(get(urlPathEqualTo(DISPUTE)).inScenario("401").whenScenarioStateIs("authorised")
                .willReturn(okJson(PayPalWireMockSupport.disputeJson("PP-D-77001", "UNDER_REVIEW"))));

        assertThat(client.getDispute("PP-D-77001").status()).isEqualTo("UNDER_REVIEW");
        server.verify(2, getRequestedFor(urlPathEqualTo(DISPUTE)));
        server.verify(2, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void aPersistent401IsReportedRatherThanLooping() {
        server.stubFor(get(urlPathEqualTo(DISPUTE)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"name\":\"AUTHENTICATION_FAILURE\",\"message\":\"Authentication failed.\"}")));

        assertThatThrownBy(() -> client.getDispute("PP-D-77001"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(401));
        server.verify(2, getRequestedFor(urlPathEqualTo(DISPUTE)));
    }

    @Test
    void connectionFailuresBecomeRetryableTransportErrors() {
        int port = server.port();
        server.stop();
        RestClient rest = PayPalWireMockSupport.restClient("http://127.0.0.1:" + port);
        PayPalRetryPolicy once = new PayPalRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
        HttpPayPalDisputesClient offline = new HttpPayPalDisputesClient(rest,
                new PayPalTokenProvider(rest, "id", "secret", PayPalWireMockSupport.JSON, once),
                PayPalWireMockSupport.JSON, new PayPalDisputeMapper(), once, "WH-DEMO-ID");

        assertThatThrownBy(() -> offline.getDispute("PP-D-77001"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isZero();
                    assertThat(e.name()).isEqualTo("CONNECTION_ERROR");
                    assertThat(e.retryable()).isTrue();
                    assertThat(e.definitelyNotApplied()).isTrue();
                });
    }

    @Test
    void webhookVerificationPassesFailsAndSurvivesAnOutage() {
        server.stubFor(post(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .withRequestBody(containing("\"webhook_id\":\"WH-DEMO-ID\""))
                .inScenario("verify").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"verification_status\":\"SUCCESS\"}")).willSetStateTo("fail"));
        server.stubFor(post(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .inScenario("verify").whenScenarioStateIs("fail")
                .willReturn(okJson("{\"verification_status\":\"FAILURE\"}")).willSetStateTo("boom"));
        server.stubFor(post(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .inScenario("verify").whenScenarioStateIs("boom")
                .willReturn(aResponse().withStatus(500)));

        WebhookHeaders headers = new WebhookHeaders("SHA256withRSA", "https://api.sandbox.paypal.com/cert.pem",
                "trans-1", "signature", "2026-11-03T12:00:00Z");
        String event = "{\"id\":\"WH-EVT-1\",\"event_type\":\"CUSTOMER.DISPUTE.CREATED\"}";

        assertThat(client.verifyWebhook(headers, event)).isEqualTo(new WebhookVerification(true, "VERIFIED"));
        assertThat(client.verifyWebhook(headers, event)).isEqualTo(new WebhookVerification(false, "FAILED"));
        // A verification outage must never be mistaken for a valid signature.
        assertThat(client.verifyWebhook(headers, event)).isEqualTo(new WebhookVerification(false, "FAILED"));

        server.verify(postRequestedFor(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .withRequestBody(containing("\"auth_algo\":\"SHA256withRSA\""))
                .withRequestBody(containing("\"transmission_id\":\"trans-1\""))
                .withRequestBody(containing("\"webhook_event\":{\"id\":\"WH-EVT-1\"")));
    }

    @Test
    void verificationIsRefusedWhenNoWebhookIdIsConfigured() {
        HttpPayPalDisputesClient noId = PayPalWireMockSupport.client(server,
                PayPalWireMockSupport.tokens(server), "");

        assertThat(noId.verifyWebhook(new WebhookHeaders("a", "b", "c", "d", "e"), "{}"))
                .isEqualTo(new WebhookVerification(false, "FAILED"));
        server.verify(0, postRequestedFor(urlPathEqualTo("/v1/notifications/verify-webhook-signature")));
    }
}
