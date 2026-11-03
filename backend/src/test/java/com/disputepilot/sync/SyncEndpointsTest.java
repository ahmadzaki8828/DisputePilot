package com.disputepilot.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.paypal.EvidenceSubmission;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import java.util.List;
import java.util.UUID;
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

/** POST /api/disputes/{id}/sync and POST /api/sync (plan §7). */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock"})
@AutoConfigureMockMvc
class SyncEndpointsTest {

    private static final String TOKEN = "test-token";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired SimulatedPayPalDisputesClient simulated;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        simulated.resetToFixtures();
    }

    private UUID seedOneDispute() throws Exception {
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", TOKEN)).andExpect(status().isOk());
        return UUID.fromString(jdbc.sql("select id from disputes where paypal_dispute_id = 'DEMO-D-1001'")
                .query(String.class).single());
    }

    @Test
    void syncingOneDisputeReturnsTheRefreshedDetailView() throws Exception {
        UUID id = seedOneDispute();
        simulated.provideEvidence("DEMO-D-1001", new EvidenceSubmission(
                List.of(new EvidenceItem("PROOF_OF_FULFILLMENT", null, "Delivered with signature"))));

        mvc.perform(post("/api/disputes/" + id + "/sync").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.paypalDisputeId").value("DEMO-D-1001"))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                // Allowed actions follow PayPal, which no longer offers provide_evidence.
                .andExpect(jsonPath("$.allowedActions").isArray())
                .andExpect(jsonPath("$.caseFile.order.id").value("ORD-1042"));

        assertThat(jdbc.sql("""
                select count(*) from audit_log where action = 'DISPUTE_SYNCED' and dispute_id = :id
                """).param("id", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void syncingAnUnknownDisputeIsAProblemJson404() throws Exception {
        mvc.perform(post("/api/disputes/00000000-0000-0000-0000-000000000000/sync")
                        .header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dispute not found"));
    }

    @Test
    void runningReconciliationOnDemandReportsWhatItDid() throws Exception {
        mvc.perform(post("/api/sync").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listed").value(6))
                .andExpect(jsonPath("$.synced").value(6))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.cursor").isString());

        assertThat(jdbc.sql("select count(*) from disputes").query(Integer.class).single()).isEqualTo(6);
    }

    @Test
    void bothSyncRoutesRequireTheAdminToken() throws Exception {
        UUID id = seedOneDispute();

        mvc.perform(post("/api/sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/disputes/" + id + "/sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/disputes/" + id + "/sync").header("X-Admin-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }
}
