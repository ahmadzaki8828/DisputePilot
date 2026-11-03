package com.disputepilot.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.EvidenceItem;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DEMO- IDs must never reach the network, and real IDs must never reach the simulator (plan §8.1). */
class RoutingPayPalDisputesClientTest {

    private WireMockServer server;
    private SimulatedPayPalDisputesClient simulated;
    private RoutingPayPalDisputesClient routing;

    @BeforeEach
    void setUp() {
        server = PayPalWireMockSupport.start();
        PayPalWireMockSupport.stubToken(server, "token-1", 3600);
        simulated = new SimulatedPayPalDisputesClient();
        routing = new RoutingPayPalDisputesClient(simulated, PayPalWireMockSupport.client(server));
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void demoIdsGoToTheSimulatorWithoutAnyHttpCall() {
        PayPalDispute d = routing.getDispute("DEMO-D-1001");

        assertThat(d.simulated()).isTrue();
        assertThat(d.reason()).isEqualTo("MERCHANDISE_OR_SERVICE_NOT_RECEIVED");
        server.verify(0, getRequestedFor(urlPathMatching("/v1/customer/disputes.*")));
    }

    @Test
    void realIdsGoToTheSandbox() {
        server.stubFor(get(urlPathEqualTo("/v1/customer/disputes/PP-D-55001"))
                .willReturn(okJson(PayPalWireMockSupport.disputeJson("PP-D-55001", "UNDER_REVIEW"))));

        PayPalDispute d = routing.getDispute("PP-D-55001");

        assertThat(d.simulated()).isFalse();
        assertThat(d.status()).isEqualTo("UNDER_REVIEW");
        server.verify(1, getRequestedFor(urlPathEqualTo("/v1/customer/disputes/PP-D-55001")));
    }

    @Test
    void actionsFollowTheSameRouting() {
        server.stubFor(post(urlPathEqualTo("/v1/customer/disputes/PP-D-55001/provide-evidence"))
                .willReturn(okJson("{}")));
        EvidenceSubmission submission = new EvidenceSubmission(
                List.of(new EvidenceItem("PROOF_OF_FULFILLMENT", null, "Delivered.")));

        routing.provideEvidence("DEMO-D-1001", submission);
        routing.provideEvidence("PP-D-55001", submission);

        assertThat(simulated.getDispute("DEMO-D-1001").status()).isEqualTo("UNDER_REVIEW");
        server.verify(1, postRequestedFor(
                urlPathEqualTo("/v1/customer/disputes/PP-D-55001/provide-evidence")));
        server.verify(0, postRequestedFor(
                urlPathEqualTo("/v1/customer/disputes/DEMO-D-1001/provide-evidence")));
    }

    @Test
    void routeForNamesTheClientThatOwnsAnId() {
        assertThat(routing.routeFor("DEMO-D-1001")).isSameAs(simulated);
        assertThat(routing.routeFor("PP-D-55001")).isInstanceOf(HttpPayPalDisputesClient.class);
        assertThat(routing.routeFor(null)).isInstanceOf(HttpPayPalDisputesClient.class);
    }

    @Test
    void listDisputesMergesSimulatedAndSandboxDisputes() {
        server.stubFor(get(urlPathEqualTo("/v1/customer/disputes")).willReturn(okJson(
                "{\"items\":[%s],\"links\":[]}"
                        .formatted(PayPalWireMockSupport.disputeJson("PP-D-55001", "UNDER_REVIEW")))));

        DisputePage page = routing.listDisputes(DisputeQuery.all());

        assertThat(page.items()).hasSize(7);
        assertThat(page.items()).extracting(PayPalDispute::paypalDisputeId).contains("DEMO-D-1001", "PP-D-55001");
        assertThat(page.items()).filteredOn(PayPalDispute::simulated).hasSize(6);
    }

    @Test
    void anUnreachableSandboxStillLeavesTheSimulatedInboxUsable() {
        server.stubFor(get(urlPathEqualTo("/v1/customer/disputes"))
                .willReturn(aResponse().withStatus(500)));

        DisputePage page = routing.listDisputes(DisputeQuery.all());

        assertThat(page.items()).hasSize(6);
        assertThat(page.items()).allMatch(PayPalDispute::simulated);
    }

    @Test
    void verificationAlwaysGoesToPayPalEvenForDemoShapedEvents() {
        server.stubFor(post(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .willReturn(okJson("{\"verification_status\":\"FAILURE\"}")));

        WebhookVerification v = routing.verifyWebhook(new WebhookHeaders("a", "b", "c", "d", "e"),
                "{\"resource\":{\"dispute_id\":\"DEMO-D-1001\"}}");

        assertThat(v).isEqualTo(new WebhookVerification(false, "FAILED"));
        server.verify(1, postRequestedFor(urlPathEqualTo("/v1/notifications/verify-webhook-signature")));
    }

    @Test
    void unknownDemoIdFailsInTheSimulatorRatherThanOnTheNetwork() {
        assertThatThrownBy(() -> routing.getDispute("DEMO-NOPE"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(404));
        server.verify(0, getRequestedFor(urlPathMatching("/v1/customer/disputes.*")));
    }
}
