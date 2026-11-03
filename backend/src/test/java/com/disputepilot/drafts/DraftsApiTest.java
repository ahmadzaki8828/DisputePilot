package com.disputepilot.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.domain.DisputeChangedEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The draft endpoints end to end against Postgres, with the fake model client. Never the network. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.drafts.auto=false"})
@AutoConfigureMockMvc
class DraftsApiTest {

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
    @Autowired ApplicationEventPublisher events;
    @Autowired DraftRepository draftRepository;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
    }

    private UUID disputeId(String paypalDisputeId) {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :pid")
                .param("pid", paypalDisputeId).query(UUID.class).single();
    }

    private JsonNode createDraft(UUID disputeId) throws Exception {
        String body = mvc.perform(post("/api/disputes/{id}/drafts", disputeId).header("X-Admin-Token", TOKEN))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private List<String> auditActions() {
        return jdbc.sql("select action from audit_log order by id").query(String.class).list();
    }

    @Test
    void generatesAGroundedDraftAndLabelsHowItWasProduced() throws Exception {
        JsonNode draft = createDraft(disputeId("DEMO-D-1001"));

        assertThat(draft.path("version").asInt()).isEqualTo(1);
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("recommendation").asString()).isEqualTo("FIGHT");
        assertThat(draft.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(draft.path("llmMode").asString()).isEqualTo("SAMPLE");
        assertThat(draft.path("provider").asString()).isEqualTo("FakeLlmClient (test double)");
        assertThat(draft.path("model").asString()).isEqualTo("fake-model");
        assertThat(draft.path("promptVersion").asString()).matches("v\\d+");
        assertThat(draft.path("inputTokens").asInt()).isEqualTo(100);
        assertThat(draft.path("editedByHuman").asBoolean()).isFalse();
        assertThat(draft.path("evidence").get(0).path("evidence_type").asString())
                .isEqualTo("PROOF_OF_FULFILLMENT");
        assertThat(draft.path("evidence").get(0).path("evidence_info").path("tracking_info").get(0)
                .path("carrier_name").asString()).isEqualTo("OTHER");
        assertThat(auditActions()).containsExactly("DRAFT_REQUESTED", "DRAFT_CREATED");
    }

    @Test
    void theLatestEndpointReturnsTheNewestVersionAndSupersedesTheOldOne() throws Exception {
        UUID id = disputeId("DEMO-D-1001");
        UUID first = UUID.fromString(createDraft(id).path("id").asString());
        JsonNode second = createDraft(id);

        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(draftRepository.find(first).orElseThrow().status().name()).isEqualTo("SUPERSEDED");

        mvc.perform(get("/api/disputes/{id}/drafts/latest", id).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.id").value(second.path("id").asString()));
    }

    @Test
    void aDisputeWithNoDraftAnswersNotFound() throws Exception {
        mvc.perform(get("/api/disputes/{id}/drafts/latest", disputeId("DEMO-D-1002"))
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("No draft"));
    }

    @Test
    void draftingAnUnknownDisputeAnswersNotFound() throws Exception {
        mvc.perform(post("/api/disputes/{id}/drafts", UUID.randomUUID()).header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void aHumanEditIsRevalidatedAndRecorded() throws Exception {
        UUID draftId = UUID.fromString(createDraft(disputeId("DEMO-D-1001")).path("id").asString());

        mvc.perform(patch("/api/drafts/{id}", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Delivered by Northline Courier and signed for on 2026-10-21.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editedByHuman").value(true))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.validation.passed").value(true))
                .andExpect(jsonPath("$.notes").value(
                        "Delivered by Northline Courier and signed for on 2026-10-21."));
        assertThat(auditActions()).contains("DRAFT_EDITED");
    }

    @Test
    void anEditThatAddsBuyerPiiFailsValidationAndNeedsReview() throws Exception {
        UUID draftId = UUID.fromString(createDraft(disputeId("DEMO-D-1001")).path("id").asString());

        mvc.perform(patch("/api/drafts/{id}", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"We emailed jordan.ellis@buyer.example about the delivery.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.validation.passed").value(false))
                .andExpect(jsonPath("$.validation.issues[0].code").value("PII_IN_TEXT"));
    }

    @Test
    void anEditThatInventsATrackingNumberFailsValidation() throws Exception {
        UUID draftId = UUID.fromString(createDraft(disputeId("DEMO-D-1001")).path("id").asString());

        mvc.perform(patch("/api/drafts/{id}", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidence":[{"evidence_type":"PROOF_OF_FULFILLMENT","evidence_info":
                                  {"tracking_info":[{"carrier_name":"OTHER","carrier_name_other":"Northline Courier",
                                   "tracking_number":"NLC-MADE-UP"}]},"notes":"Delivered."}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.issues[0].code").value("UNKNOWN_TRACKING_NUMBER"));
    }

    @Test
    void approvalNeedsValidationToPassUnlessTheMerchantOverridesIt() throws Exception {
        UUID draftId = UUID.fromString(createDraft(disputeId("DEMO-D-1001")).path("id").asString());
        mvc.perform(patch("/api/drafts/{id}", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"Call the buyer on (416) 555 0101.\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Draft did not pass validation"));

        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"overrideValidation\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(auditActions()).contains("DRAFT_APPROVED");
    }

    @Test
    void aPassingDraftIsApprovedWithoutAnOverride() throws Exception {
        UUID draftId = UUID.fromString(createDraft(disputeId("DEMO-D-1001")).path("id").asString());
        mvc.perform(post("/api/drafts/{id}/approve", draftId).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void everyDraftRouteNeedsTheAdminToken() throws Exception {
        UUID id = disputeId("DEMO-D-1001");
        mvc.perform(post("/api/disputes/{id}/drafts", id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/disputes/{id}/drafts/latest", id)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/drafts/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/drafts/{id}/approve", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
}
