package com.disputepilot.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.RecordedLlmClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The judges' zero-key path: PAYPAL_MODE=mock + DP_LLM_MODE=recorded. No API key, no network, and the draft
 * is a real response from the provider named in the recording, replayed through the real agent and validator.
 */
@SpringBootTest(properties = {
        "dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.drafts.auto=false",
        "dp.llm.mode=recorded", "dp.llm.api-key="})
@AutoConfigureMockMvc
class RecordedModeDraftTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired LlmClient llmClient;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", "test-token")).andExpect(status().isOk());
    }

    @Test
    void recordedModeIsSelectedWhenThereIsNoApiKey() {
        assertThat(llmClient).isInstanceOf(RecordedLlmClient.class);
        assertThat(((RecordedLlmClient) llmClient).caseKeys())
                .as("every starter fixture has a recording, so the whole demo works with no keys")
                .contains("DEMO-D-1001", "DEMO-D-1002", "DEMO-D-1003", "DEMO-D-1004", "DEMO-D-1005",
                        "DEMO-D-1006");
    }

    @Test
    void aDraftIsProducedFromTheRecordedProviderResponse() throws Exception {
        UUID id = jdbc.sql("select id from disputes where paypal_dispute_id = 'DEMO-D-1001'")
                .query(UUID.class).single();

        mvc.perform(post("/api/disputes/{id}/drafts", id).header("X-Admin-Token", "test-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recommendation").value("FIGHT"))
                .andExpect(jsonPath("$.validation.passed").value(true))
                .andExpect(jsonPath("$.llmMode").value("RECORDED"))
                .andExpect(jsonPath("$.provider").value("Recorded: Groq (OpenAI-compatible API)"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.evidence[0].evidence_type").value("PROOF_OF_FULFILLMENT"))
                .andExpect(jsonPath("$.evidence[0].evidence_info.tracking_info[0].tracking_number")
                        .value("NLC4417002931"))
                .andExpect(jsonPath("$.inputTokens").isNumber());
    }

    @Test
    void theUnauthorisedFixtureIsRecommendedForAcceptance() throws Exception {
        UUID id = jdbc.sql("select id from disputes where paypal_dispute_id = 'DEMO-D-1004'")
                .query(UUID.class).single();

        mvc.perform(post("/api/disputes/{id}/drafts", id).header("X-Admin-Token", "test-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recommendation").value("ACCEPT"))
                .andExpect(jsonPath("$.validation.passed").value(true));
    }
}
