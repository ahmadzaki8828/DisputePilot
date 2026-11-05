package com.disputepilot.api.analytics;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.isOneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The analytics dataset over the seeded inbox, and the agent refusing politely when no live model is set. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.llm.mode=recorded",
        "dp.llm.api-key="})
@AutoConfigureMockMvc
class AnalyticsApiTest {

    private static final String TOKEN = "test-token";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state, "
                + "transaction_links cascade").update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
    }

    @Test
    void datasetFlattensTheSeededDisputesWithLabelsAndDeadlineBuckets() throws Exception {
        mvc.perform(get("/api/analytics/dataset").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").isString())
                .andExpect(jsonPath("$.disputes.length()").value(6))
                .andExpect(jsonPath("$.disputes[*].reasonLabel").value(hasItem("Item not received")))
                .andExpect(jsonPath("$.disputes[*].simulated").value(everyItem(org.hamcrest.Matchers.is(true))))
                .andExpect(jsonPath("$.disputes[*].outcomeLabel").value(everyItem(isOneOf("Won", "Lost", "Pending"))))
                .andExpect(jsonPath("$.disputes[*].dueBucket").value(everyItem(
                        isOneOf("Overdue", "Under 48h", "2-7 days", "Over 7 days", "No deadline", "Closed"))))
                .andExpect(jsonPath("$.drafts").isArray())
                .andExpect(jsonPath("$.submissions").isArray())
                .andExpect(jsonPath("$.agent.enabled").value(false))
                .andExpect(jsonPath("$.agent.reason").isString());
    }

    @Test
    void agentAnswers503WithoutALiveModel() throws Exception {
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("live model")));
    }

    @Test
    void analyticsNeedsTheAdminToken() throws Exception {
        mvc.perform(get("/api/analytics/dataset")).andExpect(status().isUnauthorized());
    }
}
