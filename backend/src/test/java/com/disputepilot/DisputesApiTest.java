package com.disputepilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock"})
@AutoConfigureMockMvc
class DisputesApiTest {

    private static final String TOKEN = "test-token";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade").update();
    }

    private JsonNode seedAndList() throws Exception {
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(6));
        String body = mvc.perform(get("/api/disputes").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    @Test
    void flywayCreatedAllTables() {
        var tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class).list();
        assertThat(tables).contains("disputes", "webhook_events", "drafts", "submissions", "audit_log", "sync_state");
    }

    @Test
    void seedThenListReturnsTheSixFixtureDisputes() throws Exception {
        JsonNode list = seedAndList();
        assertThat(list).hasSize(6);
        for (JsonNode d : list) {
            assertThat(d.get("paypalDisputeId").asString()).startsWith("DEMO-");
            assertThat(d.get("simulated").asBoolean()).isTrue();
            assertThat(d.get("reasonLabel").asString()).isNotBlank();
            assertThat(d.get("amount").get("currency").asString()).isEqualTo("CAD");
        }
    }

    @Test
    void seedingTwiceDoesNotDuplicate() throws Exception {
        seedAndList();
        assertThat(seedAndList()).hasSize(6);
    }

    @Test
    void listFiltersByReasonAndSimulated() throws Exception {
        seedAndList();
        mvc.perform(get("/api/disputes").param("reason", "UNAUTHORISED").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/disputes").param("simulated", "false").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void detailIncludesCaseFileMessagesAndAllowedActions() throws Exception {
        JsonNode list = seedAndList();
        String id = null;
        for (JsonNode d : list) {
            if ("DEMO-D-1001".equals(d.get("paypalDisputeId").asString())) {
                id = d.get("id").asString();
            }
        }
        mvc.perform(get("/api/disputes/" + id).header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseFile.order.id").value("ORD-1042"))
                .andExpect(jsonPath("$.caseFile.shipments[0].trackingNumber").value("NLC4417002931"))
                .andExpect(jsonPath("$.caseFile.policy.merchantName").value("Maple & Thread Co."))
                .andExpect(jsonPath("$.messages[0].postedBy").value("BUYER"))
                .andExpect(jsonPath("$.requestedEvidence[0]").value("PROOF_OF_FULFILLMENT"))
                .andExpect(jsonPath("$.allowedActions").isArray());
    }

    @Test
    void unknownDisputeIsProblemJson404() throws Exception {
        mvc.perform(get("/api/disputes/00000000-0000-0000-0000-000000000000").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dispute not found"));
    }

    @Test
    void adminTokenIsEnforcedOnApiRoutes() throws Exception {
        mvc.perform(get("/api/disputes")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/disputes").header("X-Admin-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dev/seed")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/disputes").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void plannedEndpointsReturn501ProblemJson() throws Exception {
        mvc.perform(get("/api/metrics").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(501));
        mvc.perform(post("/api/disputes/abc/drafts").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotImplemented());
        // POST /webhooks/paypal is no longer a stub: Session A's controller took the route over, so an
        // unusable body is now answered with 400 rather than 501. Other stub routes are unaffected.
        mvc.perform(post("/webhooks/paypal").content("{}").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/system").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotImplemented());
    }
}
