package com.disputepilot.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.LlmRequest;
import com.disputepilot.agent.LlmResult;
import com.disputepilot.agent.RetryPolicy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A live provider that asks for a 20 minute wait: drafts must not hang, and must say so honestly. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.drafts.auto=false"})
@AutoConfigureMockMvc
class BusyProviderDraftTest {

    static final AtomicInteger CALLS = new AtomicInteger();

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @TestConfiguration
    static class RateLimitedModel {
        @Bean
        @Primary
        LlmClient rateLimited() {
            return new LlmClient() {
                @Override
                public LlmResult run(LlmRequest request) {
                    CALLS.incrementAndGet();
                    throw new RetryPolicy.RateLimitedException("Groq", "quota", Duration.ofMinutes(20));
                }

                @Override
                public boolean isLive() {
                    return true;
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() throws Exception {
        CALLS.set(0);
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", "test-token")).andExpect(status().isOk());
    }

    private UUID id(String pid) {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :p").param("p", pid)
                .query(UUID.class).single();
    }

    @Test
    void withARecordingTheDraftFallsBackToItAtOnce() throws Exception {
        mvc.perform(post("/api/disputes/{id}/drafts", id("DEMO-D-1001")).header("X-Admin-Token", "test-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.llmMode").value("RECORDED"));
        assertThat(CALLS).hasValue(1);
    }

    @Test
    void withoutARecordingASavedBusyStateReplacesTheDraft() throws Exception {
        jdbc.sql("update disputes set paypal_dispute_id = 'DEMO-NO-RECORDING' where paypal_dispute_id = 'DEMO-D-1001'")
                .update();
        UUID id = id("DEMO-NO-RECORDING");
        mvc.perform(post("/api/disputes/{id}/drafts", id).header("X-Admin-Token", "test-token"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.rationale").value(org.hamcrest.Matchers.startsWith("AI is busy, try again around")))
                .andExpect(jsonPath("$.validation.issues[0].code").value("AI_BUSY"));
        assertThat(CALLS).hasValue(1);

        // Regenerate works (a new version), and the placeholder can never be approved, even with an override.
        mvc.perform(post("/api/disputes/{id}/drafts", id).header("X-Admin-Token", "test-token"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
        UUID draft = jdbc.sql("select id from drafts where dispute_id = :d and status <> 'SUPERSEDED'")
                .param("d", id).query(UUID.class).single();
        mvc.perform(post("/api/drafts/{id}/approve", draft).header("X-Admin-Token", "test-token")
                        .contentType("application/json").content("{\"overrideValidation\":true}"))
                .andExpect(status().isConflict());
    }
}
