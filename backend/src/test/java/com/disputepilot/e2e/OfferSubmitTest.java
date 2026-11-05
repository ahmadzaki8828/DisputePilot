package com.disputepilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.domain.Citation;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.DraftStatus;
import com.disputepilot.domain.LlmMode;
import com.disputepilot.domain.Money;
import com.disputepilot.domain.Offer;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import com.disputepilot.drafts.DraftRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An approved OFFER draft goes to PayPal's make-offer call (here the simulated PayPal, in mock mode) with the same
 * guards as evidence: Idempotency-Key, APPROVED draft, PayPal's allowed actions, and an audit trail.
 */
@SpringBootTest(properties = {
        "dp.admin-token=test-token",
        "dp.paypal.mode=mock",
        "dp.drafts.auto=false",
        "dp.demo.action-limit=100"})
@AutoConfigureMockMvc
class OfferSubmitTest {

    private static final String TOKEN = "test-token";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @TestConfiguration
    static class FakeModel {
        @Bean
        @Primary
        LlmClient fakeLlmClient() {
            return new FakeLlmClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired DraftRepository drafts;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade").update();
    }

    /** A fresh simulated not-as-described dispute (CAD 129.00), which PayPal lets the seller make an offer on. */
    private UUID simulatedSnad() throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/demo/disputes").header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"NOT_AS_DESCRIBED\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return UUID.fromString(created.path("id").asString());
    }

    private Draft offerDraft(UUID disputeId, Offer offer) {
        return drafts.insert(new Draft(UUID.randomUUID(), disputeId, drafts.nextVersion(disputeId), DraftStatus.DRAFT,
                Recommendation.OFFER, new BigDecimal("0.6"), "A partial refund settles a colour-variation claim.",
                List.of(), "The store offers a partial refund for the colour variation described in its policy.",
                List.of(new Citation("policy:descriptions", "policy", "Product descriptions")), List.of(), offer,
                new ValidationResult(true, List.of()), LlmMode.SAMPLE, null, null, "test", null, null, null,
                false, false, Instant.now()));
    }

    private void approve(UUID draftId, boolean override) throws Exception {
        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"overrideValidation\":" + override + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void anApprovedOfferIsSentThroughMakeOfferOnceWithItsAuditTrail() throws Exception {
        UUID disputeId = simulatedSnad();
        Draft draft = offerDraft(disputeId, new Offer(new Money("CAD", new BigDecimal("20.00")), Offer.REFUND));
        mvc.perform(get("/api/disputes/{id}/drafts/latest", disputeId).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.offer.amount.value").value(20.00))
                .andExpect(jsonPath("$.offer.offerType").value("REFUND"));
        approve(draft.id(), false);

        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) { // the second call replays the stored result
            mvc.perform(post("/api/drafts/{id}/submit", draft.id()).header("X-Admin-Token", TOKEN)
                            .header("Idempotency-Key", key))
                    .andExpect(status().is2xxSuccessful())
                    .andExpect(jsonPath("$.action").value("MAKE_OFFER"))
                    .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        }

        assertThat(jdbc.sql("select count(*) from submissions").query(Integer.class).single()).isEqualTo(1);
        JsonNode request = json.readTree(jdbc.sql("select request_payload::text from submissions")
                .query(String.class).single());
        assertThat(request.path("offer_type").asString()).isEqualTo("REFUND");
        assertThat(request.path("offer_amount").path("value").asString()).isEqualTo("20.00");
        assertThat(request.path("offer_amount").path("currency_code").asString()).isEqualTo("CAD");
        assertThat(request.has("return_shipping_address")).isFalse();
        assertThat(jdbc.sql("select status from disputes where id = :id").param("id", disputeId)
                .query(String.class).single()).isEqualTo("WAITING_FOR_BUYER_RESPONSE");
        assertThat(jdbc.sql("select status from drafts where id = :id").param("id", draft.id())
                .query(String.class).single()).isEqualTo("SUBMITTED");
        assertThat(jdbc.sql("select action from audit_log order by id").query(String.class).list())
                .containsSubsequence("DRAFT_APPROVED", "SUBMISSION_STARTED", "SUBMISSION_SUCCEEDED",
                        "SUBMISSION_REPLAYED");
    }

    @Test
    void aRefundWithReturnCarriesTheStoresReturnAddress() throws Exception {
        UUID disputeId = simulatedSnad();
        Draft draft = offerDraft(disputeId,
                new Offer(new Money("CAD", new BigDecimal("68.00")), Offer.REFUND_WITH_RETURN));
        approve(draft.id(), false);
        mvc.perform(post("/api/drafts/{id}/submit", draft.id()).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().is2xxSuccessful());

        JsonNode address = json.readTree(jdbc.sql("select request_payload::text from submissions")
                .query(String.class).single()).path("return_shipping_address");
        assertThat(address.path("admin_area_2").asString()).isEqualTo("Toronto");
        assertThat(address.path("country_code").asString()).isEqualTo("CA");
    }

    @Test
    void anOfferAboveTheDisputedAmountIsRefusedEvenWhenApprovedOverValidation() throws Exception {
        UUID disputeId = simulatedSnad();
        Draft draft = offerDraft(disputeId, new Offer(new Money("CAD", new BigDecimal("500.00")), Offer.REFUND));
        approve(draft.id(), true);
        mvc.perform(post("/api/drafts/{id}/submit", draft.id()).header("X-Admin-Token", TOKEN)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Offer cannot be sent"));
        assertThat(jdbc.sql("select count(*) from submissions").query(Integer.class).single()).isZero();
    }

    @Test
    void editingTheOfferRevalidatesIt() throws Exception {
        UUID disputeId = simulatedSnad();
        Draft draft = offerDraft(disputeId, new Offer(new Money("CAD", new BigDecimal("20.00")), Offer.REFUND));
        mvc.perform(patch("/api/drafts/{id}", draft.id()).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offer\":{\"amount\":{\"currency\":\"CAD\",\"value\":999},\"offerType\":\"REFUND\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.validation.issues[0].code").value("OFFER_EXCEEDS_DISPUTED_AMOUNT"));
    }
}
