package com.disputepilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
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
 * "Simulate a dispute" (plan §13 D.3): a simulated DEMO- dispute travels the same webhook -> outbox -> sync ->
 * auto-draft path as a real one, in mock mode, with the fake model. Also the reset and the per-visitor limit.
 */
@SpringBootTest(properties = {
        "dp.admin-token=test-token",
        "dp.paypal.mode=mock",
        "dp.drafts.auto=true",
        "dp.drafts.async=false",
        "dp.demo.action-limit=4"})
@AutoConfigureMockMvc
class DemoFlowTest {

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

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade").update();
    }

    private JsonNode simulate(String template, String clientIp) throws Exception {
        return json.readTree(mvc.perform(post("/api/demo/disputes").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", clientIp).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"" + template + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aSimulatedDisputeTravelsTheWholePipelineAndGetsADraft() throws Exception {
        JsonNode created = simulate("ITEM_NOT_RECEIVED", "203.0.113.10");

        assertThat(created.path("simulated").asBoolean()).isTrue();
        assertThat(created.path("paypalDisputeId").asString()).startsWith("DEMO-D-9");
        assertThat(created.path("reason").asString()).isEqualTo("MERCHANDISE_OR_SERVICE_NOT_RECEIVED");
        UUID id = UUID.fromString(created.path("id").asString());

        // A webhook event was stored (verification skipped: nothing arrived over the network) and processed.
        assertThat(jdbc.sql("select verification || ':' || (processed_at is not null) from webhook_events")
                .query(String.class).single()).isEqualTo("SKIPPED_SIMULATED:true");
        // The agent drafted automatically; the draft cites the clone's own records.
        JsonNode draft = json.readTree(mvc.perform(get("/api/disputes/{id}/drafts/latest", id)
                        .header("X-Admin-Token", TOKEN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(draft.path("recommendation").asString()).isEqualTo("FIGHT");
        assertThat(draft.path("validation").path("passed").asBoolean()).isTrue();
        assertThat(jdbc.sql("select action from audit_log order by id").query(String.class).list())
                .containsSubsequence("WEBHOOK_RECEIVED", "DISPUTE_SYNCED", "DRAFT_REQUESTED", "DRAFT_CREATED");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'DEMO_DISPUTE_CREATED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theInjectionTemplateCarriesTheBuyersInstructionAsDataAndItsOwnOrder() throws Exception {
        JsonNode created = simulate("PROMPT_INJECTION", "203.0.113.11");
        JsonNode detail = json.readTree(mvc.perform(get("/api/disputes/{id}", created.path("id").asString())
                        .header("X-Admin-Token", TOKEN)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(detail.path("messages").get(0).path("content").asString()).contains("ignore all previous instructions");
        assertThat(detail.path("caseFile").path("order").path("id").asString()).isEqualTo("ORD-2005");
        assertThat(detail.path("caseFile").path("shipments").get(0).path("trackingNumber").asString())
                .isEqualTo("NLC4417003148");
    }

    @Test
    void anUnknownTemplateIsABadRequest() throws Exception {
        mvc.perform(post("/api/demo/disputes").header("X-Admin-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"template\":\"NOPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetRemovesSimulatedDisputesAndReseedsTheStarterSet() throws Exception {
        simulate("UNAUTHORISED", "203.0.113.12");
        simulate("DUPLICATE_CHARGE", "203.0.113.12");
        jdbc.sql("""
                insert into disputes (id, paypal_dispute_id, simulated, reason, status, amount_value, amount_currency,
                    requested_evidence, allowed_actions, raw)
                values (gen_random_uuid(), 'PP-REAL-1', false, 'UNAUTHORISED', 'WAITING_FOR_SELLER_RESPONSE', 10, 'CAD',
                    '[]', '{}', '{}')""").update();

        mvc.perform(post("/api/demo/reset").header("X-Admin-Token", TOKEN).header("X-Forwarded-For", "203.0.113.12"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seeded").value(6));

        assertThat(jdbc.sql("select count(*) from disputes where simulated").query(Integer.class).single()).isEqualTo(6);
        assertThat(jdbc.sql("select count(*) from disputes where paypal_dispute_id like 'DEMO-D-9%'")
                .query(Integer.class).single()).isZero();
        // A real sandbox dispute is never touched by a demo reset.
        assertThat(jdbc.sql("select count(*) from disputes where paypal_dispute_id = 'PP-REAL-1'")
                .query(Integer.class).single()).isEqualTo(1);
        // The starters got their drafts through the same auto-draft path.
        assertThat(jdbc.sql("select count(*) from drafts").query(Integer.class).single()).isEqualTo(6);
    }

    @Test
    void tooManyDemoActionsFromOneVisitorAnswer429ButAnotherVisitorIsUnaffected() throws Exception {
        for (int i = 0; i < 4; i++) {
            simulate("NOT_AS_DESCRIBED", "198.51.100.7");
        }
        mvc.perform(post("/api/demo/disputes").header("X-Admin-Token", TOKEN).header("X-Forwarded-For", "198.51.100.7")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"template\":\"NOT_AS_DESCRIBED\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Demo limit reached"));
        mvc.perform(post("/api/demo/reset").header("X-Admin-Token", TOKEN).header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isTooManyRequests());

        simulate("NOT_AS_DESCRIBED", "198.51.100.8");
    }

    @Test
    void theStartersAreAvailableThroughSeedOnStartLogic() throws Exception {
        assertThat(List.of("ITEM_NOT_RECEIVED", "NOT_AS_DESCRIBED", "UNAUTHORISED", "DUPLICATE_CHARGE",
                "PROMPT_INJECTION")).allSatisfy(t -> {
            try {
                simulate(t, "192.0.2." + Math.abs(t.hashCode() % 200));
            } catch (Exception e) {
                throw new AssertionError(t, e);
            }
        });
    }
}
