package com.disputepilot.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.paypal.HttpPayPalDisputesClient;
import com.disputepilot.paypal.PayPalClientConfig;
import com.disputepilot.paypal.PayPalDisputeMapper;
import com.disputepilot.paypal.PayPalDisputesClient;
import com.disputepilot.paypal.PayPalRetryPolicy;
import com.disputepilot.paypal.PayPalTokenProvider;
import com.disputepilot.paypal.RoutingPayPalDisputesClient;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import com.disputepilot.webhook.DisputeOutboxWorker;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole path, with PayPal replaced by WireMock and the model by {@link FakeLlmClient} (never a live model):
 * webhook -> outbox -> dispute -> automatic draft -> approve -> submit -> the multipart reaches PayPal -> the
 * dispute moves on -> the audit trail has every step. Also: the same Idempotency-Key never calls PayPal twice.
 *
 * <p>The multipart {@code provide-evidence} call is only exercised against WireMock here; it is tested against
 * the real sandbox after the Render deploy.
 */
@SpringBootTest(properties = {
        "dp.admin-token=test-token",
        "dp.paypal.mode=sandbox",
        "dp.paypal.client-id=test-client",
        "dp.paypal.client-secret=test-secret",
        "dp.paypal.webhook-id=WEBHOOK-TEST",
        "spring.main.allow-bean-definition-overriding=true", // swap the app's PayPal client for the WireMock one
        "dp.drafts.auto=true",          // the test.properties default is off; this test is about auto-drafting
        "dp.drafts.async=false"})
@AutoConfigureMockMvc
class EndToEndSubmitTest {

    private static final String TOKEN = "test-token";
    private static final String PAYPAL_ID = "PP-D-E2E-0001";
    private static final String DISPUTE_PATH = "/v1/customer/disputes/" + PAYPAL_ID;
    private static final String SCENARIO = "dispute-state";
    private static final WireMockServer PAYPAL = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYPAL.start();
    }

    @AfterAll
    static void stop() {
        PAYPAL.stop();
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    /** PayPal -> WireMock (real HTTP client, real multipart); model -> the canned fake. */
    @TestConfiguration
    static class Doubles {
        @Bean
        @Primary
        LlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }

        @Bean
        @Primary
        PayPalDisputesClient payPalDisputesClient(SimulatedPayPalDisputesClient simulated, JsonMapper json,
                                            PayPalDisputeMapper mapper) {
            var rest = PayPalClientConfig.restClient(PAYPAL.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(10));
            var retry = new PayPalRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(2));
            var tokens = new PayPalTokenProvider(rest, "test-client", "test-secret", json, retry);
            return new RoutingPayPalDisputesClient(simulated,
                    new HttpPayPalDisputesClient(rest, tokens, json, mapper, retry, "WEBHOOK-TEST"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired DisputeOutboxWorker outbox;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade").update();
        PAYPAL.resetAll();
        PAYPAL.stubFor(WireMock.post(urlPathEqualTo("/v1/oauth2/token")).willReturn(okJson("""
                {"access_token":"tok","token_type":"Bearer","expires_in":3600}""")));
        PAYPAL.stubFor(WireMock.post(urlPathEqualTo("/v1/notifications/verify-webhook-signature"))
                .willReturn(okJson("{\"verification_status\":\"SUCCESS\"}")));
        // GET dispute: waiting for the seller until provide-evidence is accepted, then under review.
        PAYPAL.stubFor(WireMock.get(urlPathEqualTo(DISPUTE_PATH)).inScenario(SCENARIO).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson(disputeJson("WAITING_FOR_SELLER_RESPONSE"))));
        PAYPAL.stubFor(WireMock.get(urlPathEqualTo(DISPUTE_PATH)).inScenario(SCENARIO).whenScenarioStateIs("SUBMITTED")
                .willReturn(okJson(disputeJson("UNDER_REVIEW"))));
        PAYPAL.stubFor(WireMock.post(urlPathEqualTo(DISPUTE_PATH + "/provide-evidence")).inScenario(SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("SUBMITTED")
                .willReturn(okJson("{\"links\":[]}")));
        PAYPAL.stubFor(WireMock.post(urlPathEqualTo(DISPUTE_PATH + "/accept-claim")).willReturn(aResponse().withStatus(200)));
    }

    /** Fixture dispute 1001 re-labelled as a real (non-DEMO) sandbox dispute for the same order. */
    private String disputeJson(String status) throws Exception {
        String raw = new String(new ClassPathResource("fixtures/paypal/disputes/demo-d-1001-inr-delivered-signature.json")
                .getContentAsByteArray(), StandardCharsets.UTF_8).replace("DEMO-D-1001", PAYPAL_ID);
        ObjectNode d = (ObjectNode) json.readTree(raw);
        d.remove("_note");
        d.put("status", status);
        d.put("seller_response_due_date", java.time.Instant.now().plus(Duration.ofDays(9)).toString());
        if (!"WAITING_FOR_SELLER_RESPONSE".equals(status)) {
            ArrayNode keep = json.createArrayNode();
            d.path("links").forEach(l -> {
                String rel = l.path("rel").asString();
                if (rel.equals("self") || rel.equals("send_message")) {
                    keep.add(l);
                }
            });
            d.set("links", keep);
        }
        return json.writeValueAsString(d);
    }

    private void deliverWebhook(String eventId) throws Exception {
        String event = """
                {"id":"%s","event_version":"1.0","create_time":"2026-11-03T12:00:00Z","resource_type":"dispute",
                 "event_type":"CUSTOMER.DISPUTE.CREATED","summary":"A buyer opened a dispute",
                 "resource":{"dispute_id":"%s","status":"WAITING_FOR_SELLER_RESPONSE"}}""".formatted(eventId, PAYPAL_ID);
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(event)
                        .header("PAYPAL-AUTH-ALGO", "SHA256withRSA").header("PAYPAL-CERT-URL", "https://example.test/c")
                        .header("PAYPAL-TRANSMISSION-ID", "t-1").header("PAYPAL-TRANSMISSION-SIG", "sig")
                        .header("PAYPAL-TRANSMISSION-TIME", "2026-11-03T12:00:00Z"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verification").value("VERIFIED"));
    }

    private UUID disputeId() {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :p").param("p", PAYPAL_ID)
                .query(UUID.class).single();
    }

    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("X-Admin-Token", TOKEN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private List<String> auditActions() {
        return jdbc.sql("select action from audit_log order by id").query(String.class).list();
    }

    private int providedEvidenceCalls() {
        return PAYPAL.findAll(postRequestedFor(urlPathEqualTo(DISPUTE_PATH + "/provide-evidence"))).size();
    }

    @Test
    void webhookToDraftToApproveToSubmit() throws Exception {
        // 1. The webhook arrives; the outbox worker re-fetches the dispute; the agent drafts on its own.
        deliverWebhook("WH-E2E-0001");
        assertThat(outbox.processPending()).isEqualTo(1);

        UUID id = disputeId();
        JsonNode detail = getJson("/api/disputes/" + id);
        assertThat(detail.path("status").asString()).isEqualTo("WAITING_FOR_SELLER_RESPONSE");
        assertThat(detail.path("simulated").asBoolean()).isFalse();
        assertThat(detail.path("allowedActions").toString()).contains("PROVIDE_EVIDENCE");

        JsonNode draft = getJson("/api/disputes/" + id + "/drafts/latest");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("recommendation").asString()).isEqualTo("FIGHT");
        assertThat(draft.path("validation").path("passed").asBoolean()).isTrue();
        UUID draftId = UUID.fromString(draft.path("id").asString());

        // 2. Submitting before a human approves is refused, and PayPal is not called.
        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", "key-before-approval"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Draft is not approved"));
        assertThat(providedEvidenceCalls()).isZero();

        // 3. A human approves; a missing key is refused.
        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Idempotency-Key required"));
        assertThat(providedEvidenceCalls()).isZero();

        // 4. Submit: PayPal receives the multipart evidence exactly once.
        String key = "E2E-" + UUID.randomUUID();
        String first = mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.action").value("PROVIDE_EVIDENCE"))
                .andExpect(jsonPath("$.idempotencyKey").value(key))
                .andReturn().getResponse().getContentAsString();
        assertThat(providedEvidenceCalls()).isEqualTo(1);
        var sent = PAYPAL.findAll(postRequestedFor(urlPathEqualTo(DISPUTE_PATH + "/provide-evidence"))).get(0);
        assertThat(sent.getHeader("Content-Type")).startsWith("multipart/form-data");
        assertThat(sent.getBodyAsString()).contains("name=\"input\"", "PROOF_OF_FULFILLMENT", "NLC4417002931",
                "Northline Courier");

        // 5. The dispute moved on at PayPal and in our database; the draft is SUBMITTED.
        assertThat(getJson("/api/disputes/" + id).path("status").asString()).isEqualTo("UNDER_REVIEW");
        assertThat(getJson("/api/disputes/" + id + "/drafts/latest").path("status").asString()).isEqualTo("SUBMITTED");

        // 6. The same key again returns the stored result and does not call PayPal again.
        String replay = mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(replay).path("id").asString()).isEqualTo(json.readTree(first).path("id").asString());
        assertThat(providedEvidenceCalls()).isEqualTo(1);

        // A different key for the already-submitted draft is refused too.
        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", "a-brand-new-key"))
                .andExpect(status().isConflict());
        assertThat(providedEvidenceCalls()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from submissions").query(Integer.class).single()).isEqualTo(1);

        // 7. Every step is in the audit trail, in order.
        assertThat(auditActions()).containsSubsequence("WEBHOOK_RECEIVED", "DISPUTE_SYNCED", "DRAFT_REQUESTED",
                "DRAFT_CREATED", "DRAFT_APPROVED", "SUBMISSION_STARTED", "SUBMISSION_SUCCEEDED", "SUBMISSION_REPLAYED");
        JsonNode trail = getJson("/api/audit?disputeId=" + id);
        assertThat(trail.size()).isGreaterThanOrEqualTo(7);
        assertThat(getJson("/api/metrics").path("submitted").asInt()).isEqualTo(1);
    }

    @Test
    void aFailedPayPalCallIsRecordedAndTheSameKeyMayRetry() throws Exception {
        deliverWebhook("WH-E2E-0002");
        outbox.processPending();
        UUID id = disputeId();
        UUID draftId = UUID.fromString(getJson("/api/disputes/" + id + "/drafts/latest").path("id").asString());
        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN)).andExpect(status().isOk());

        PAYPAL.stubFor(WireMock.post(urlPathEqualTo(DISPUTE_PATH + "/provide-evidence")).inScenario(SCENARIO)
                .whenScenarioStateIs(Scenario.STARTED).atPriority(1).willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"name":"UNPROCESSABLE_ENTITY","message":"The requested action is not valid.",
                                 "debug_id":"dbg-777","details":[{"issue":"DISPUTE_STATE_NOT_VALID_FOR_ACTION"}]}""")));

        String key = "retry-" + UUID.randomUUID();
        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN).header("Idempotency-Key", key))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.paypalDebugId").value("dbg-777"));
        assertThat(jdbc.sql("select status || ':' || paypal_debug_id from submissions").query(String.class).single())
                .isEqualTo("FAILED:dbg-777");
        assertThat(auditActions()).contains("SUBMISSION_FAILED");
        // The draft stays approved so the merchant can retry.
        assertThat(getJson("/api/disputes/" + id + "/drafts/latest").path("status").asString()).isEqualTo("APPROVED");

        // PayPal recovers; the same key retries and succeeds, still leaving exactly one submission row.
        PAYPAL.removeStubMapping(PAYPAL.getStubMappings().stream()
                .filter(m -> m.getPriority() != null && m.getPriority() == 1).findFirst().orElseThrow());
        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN).header("Idempotency-Key", key))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.sql("select count(*) from submissions").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anAcceptDraftIsSubmittedAsAnAcceptedClaim() throws Exception {
        deliverWebhook("WH-E2E-0003");
        outbox.processPending();
        UUID id = disputeId();
        UUID draftId = UUID.fromString(getJson("/api/disputes/" + id + "/drafts/latest").path("id").asString());
        // The merchant (or the agent) decided to accept: flip the stored recommendation, then approve and submit.
        jdbc.sql("update drafts set recommendation = 'ACCEPT', evidence = '[]'::jsonb where id = :id")
                .param("id", draftId).update();
        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN)).andExpect(status().isOk());

        mvc.perform(post("/api/drafts/{id}/submit", draftId).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", "accept-" + UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value("ACCEPT_CLAIM"));
        assertThat(PAYPAL.findAll(postRequestedFor(urlPathEqualTo(DISPUTE_PATH + "/accept-claim")))).hasSize(1);
        assertThat(providedEvidenceCalls()).isZero();
    }
}
