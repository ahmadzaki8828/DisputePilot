package com.disputepilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.LlmException;
import com.disputepilot.agent.LlmRequest;
import com.disputepilot.agent.LlmResult;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Groq's free tier answers 429 and the public demo has a daily cap and a per-visitor limit (plan §9.2 step 7).
 * In each case the draft falls back to a labelled recorded response when the dispute has a recording, and
 * otherwise the caller gets a friendly 429. The "live" client here is a test double; nothing touches the network.
 */
@SpringBootTest(properties = {
        "dp.admin-token=test-token",
        "dp.paypal.mode=mock",
        "dp.drafts.auto=false",
        "dp.demo.rate-limit=1"})
@AutoConfigureMockMvc
class LlmFallbackTest {

    private static final String TOKEN = "test-token";
    static final AtomicBoolean PROVIDER_RATE_LIMITED = new AtomicBoolean();
    static final AtomicBoolean PROVIDER_DOWN = new AtomicBoolean();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    /** A "live" model: works, or answers 429 like a rate-limited provider. */
    static class FlakyLiveClient extends FakeLlmClient {
        @Override
        public boolean isLive() {
            return true;
        }

        @Override
        public LlmResult run(LlmRequest request) {
            if (PROVIDER_DOWN.get()) {
                throw new LlmException("Test provider", 503, "Service unavailable");
            }
            if (PROVIDER_RATE_LIMITED.get()) {
                throw new LlmException("Test provider", 429, "Rate limit reached for tokens per minute");
            }
            return super.run(request);
        }
    }

    @TestConfiguration
    static class Live {
        @Bean
        @Primary
        LlmClient liveClient() {
            return new FlakyLiveClient();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void seed() throws Exception {
        PROVIDER_RATE_LIMITED.set(false);
        PROVIDER_DOWN.set(false);
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade").update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
    }

    private UUID id(String paypalId) {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :p").param("p", paypalId)
                .query(UUID.class).single();
    }

    private JsonNode draft(UUID id, String ip, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post("/api/disputes/{id}/drafts", id).header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", ip).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aRateLimitedProviderFallsBackToALabelledRecordedResponse() throws Exception {
        PROVIDER_RATE_LIMITED.set(true);

        JsonNode d = draft(id("DEMO-D-1001"), "203.0.113.50", 201);

        assertThat(d.path("llmMode").asString()).isEqualTo("RECORDED");
        assertThat(d.path("provider").asString()).startsWith("Recorded: ");
        assertThat(d.path("model").asString()).isEqualTo("openai/gpt-oss-120b");
        assertThat(d.path("recommendation").asString()).isEqualTo("FIGHT");
        assertThat(d.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(jdbc.sql("select action from audit_log where action = 'LLM_FALLBACK'").query(String.class).list())
                .hasSize(1);
    }

    @Test
    void aClonedDemoDisputeReplaysItsTemplatesRecordingAndStillValidates() throws Exception {
        PROVIDER_RATE_LIMITED.set(true);
        JsonNode created = json.readTree(mvc.perform(post("/api/demo/disputes").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.51").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"UNAUTHORISED\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        JsonNode d = draft(UUID.fromString(created.path("id").asString()), "203.0.113.51", 201);

        assertThat(d.path("llmMode").asString()).isEqualTo("RECORDED");
        // The recording cites dispute:DEMO-D-1004; it was rewritten to the clone's own ID, so it is grounded.
        assertThat(d.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(d.toString()).doesNotContain("DEMO-D-1004");
    }

    @Test
    void withNoRecordingTheCallerGetsAFriendly429() throws Exception {
        PROVIDER_RATE_LIMITED.set(true);
        jdbc.sql("""
                insert into disputes (id, paypal_dispute_id, simulated, reason, status, life_cycle_stage, amount_value,
                    amount_currency, seller_response_due_at, disputed_transaction_id, requested_evidence,
                    allowed_actions, raw)
                select gen_random_uuid(), 'PP-NO-RECORDING', false, reason, status, life_cycle_stage, amount_value,
                    amount_currency, seller_response_due_at, disputed_transaction_id, requested_evidence,
                    allowed_actions, raw from disputes where paypal_dispute_id = 'DEMO-D-1001'""").update();

        mvc.perform(post("/api/disputes/{id}/drafts", id("PP-NO-RECORDING")).header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.70"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("The AI is busy right now"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("try again")));
        assertThat(jdbc.sql("select count(*) from drafts").query(Integer.class).single()).isZero();
    }

    @Test
    void theSecondLiveDraftFromOneVisitorInAnHourIsServedFromARecording() throws Exception {
        JsonNode first = draft(id("DEMO-D-1001"), "203.0.113.60", 201);
        assertThat(first.path("provider").asString()).isEqualTo("FakeLlmClient (test double)");

        JsonNode second = draft(id("DEMO-D-1001"), "203.0.113.60", 201);
        assertThat(second.path("llmMode").asString()).isEqualTo("RECORDED");
        assertThat(second.path("provider").asString()).startsWith("Recorded: ");

        // Another visitor still gets the live model.
        JsonNode other = draft(id("DEMO-D-1001"), "203.0.113.61", 201);
        assertThat(other.path("provider").asString()).isEqualTo("FakeLlmClient (test double)");
    }

    @Test
    void aProviderFailureThatIsNotRateLimitingIsAGatewayErrorNotASilentFallback() throws Exception {
        PROVIDER_DOWN.set(true);

        mvc.perform(post("/api/disputes/{id}/drafts", id("DEMO-D-1001")).header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.71"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.title").value("The AI provider failed"));
        assertThat(jdbc.sql("select count(*) from drafts").query(Integer.class).single()).isZero();
    }
}
