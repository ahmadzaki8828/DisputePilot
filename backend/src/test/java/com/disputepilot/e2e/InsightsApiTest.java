package com.disputepilot.e2e;

import static org.assertj.core.api.Assertions.assertThat;
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

/** system, metrics, audit, the eval report and link-transaction, with the real recorded-response client. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock"})
@AutoConfigureMockMvc
class InsightsApiTest {

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
    void systemReportsRecordedResponsesHonestlyWhenNoKeyIsSet() throws Exception {
        mvc.perform(get("/api/system").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paypalMode").value("mock"))
                .andExpect(jsonPath("$.llmMode").value("recorded"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.provider").value(org.hamcrest.Matchers.startsWith("Recorded: ")))
                .andExpect(jsonPath("$.simulatedEnabled").value(true));
    }

    @Test
    void theLatestEvalReportIsServedInTheContractShape() throws Exception {
        mvc.perform(get("/api/evals/latest").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("live"))
                .andExpect(jsonPath("$.cases").value(12))
                .andExpect(jsonPath("$.recommendationAccuracy").value(1.0))
                .andExpect(jsonPath("$.injectionResistance").value(1.0))
                .andExpect(jsonPath("$.results.length()").value(12))
                .andExpect(jsonPath("$.results[0].caseId").value("01-inr-delivered-signature"))
                .andExpect(jsonPath("$.results[0].expectedRecommendation").value("FIGHT"))
                // The report is unpriced, so the API says so with null instead of inventing a cost.
                .andExpect(jsonPath("$.estimatedCostPerDraftUsd").doesNotExist());
    }

    @Test
    void metricsCountTheSeededInbox() throws Exception {
        mvc.perform(get("/api/metrics").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDisputes").value(6))
                .andExpect(jsonPath("$.draftsReady").value(0))
                .andExpect(jsonPath("$.submitted").value(0))
                .andExpect(jsonPath("$.winRate").doesNotExist());
    }

    @Test
    void auditListsEntriesOldestFirstAndFiltersByDispute() throws Exception {
        jdbc.sql("insert into audit_log (actor, action, details) values ('system', 'A_FIRST', '{}'), ('user', 'B_SECOND', '{\"k\":1}')").update();
        mvc.perform(get("/api/audit").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("A_FIRST"))
                .andExpect(jsonPath("$[1].details.k").value(1));
        mvc.perform(get("/api/audit").param("disputeId", "00000000-0000-0000-0000-000000000000")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void linkingATransactionMakesTheDisputeFindTheOrder() throws Exception {
        String disputeId = jdbc.sql("select id from disputes where paypal_dispute_id = 'DEMO-D-1002'")
                .query(String.class).single();
        // Pretend a real sandbox dispute whose transaction the merchant's records do not know.
        jdbc.sql("update disputes set disputed_transaction_id = 'SANDBOX-TXN-XYZ' where id = cast(:id as uuid)")
                .param("id", disputeId).update();
        mvc.perform(get("/api/disputes/{id}", disputeId).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.caseFile.order").doesNotExist());

        mvc.perform(post("/api/dev/link-transaction").header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerTransactionId\":\"SANDBOX-TXN-XYZ\",\"orderId\":\"ORD-1042\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/disputes/{id}", disputeId).header("X-Admin-Token", TOKEN))
                .andExpect(jsonPath("$.caseFile.order.id").value("ORD-1042"))
                .andExpect(jsonPath("$.caseFile.shipments[0].trackingNumber").value("NLC4417002931"));
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'TRANSACTION_LINKED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void linkingRejectsAnUnknownOrderAndMissingFields() throws Exception {
        mvc.perform(post("/api/dev/link-transaction").header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerTransactionId\":\"T-1\",\"orderId\":\"ORD-NOPE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/dev/link-transaction").header("X-Admin-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"ORD-1042\"}"))
                .andExpect(status().isBadRequest());
    }
}
