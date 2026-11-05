package com.disputepilot.submission;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
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

/** DEMO-D-1005's order already has refund REF-5001: accepting the claim must be refused even when approved. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.drafts.auto=false"})
@AutoConfigureMockMvc
class DoubleRefundSubmissionTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", "test-token")).andExpect(status().isOk());
    }

    private void approvedDraft(String disputeId, String recommendation) {
        UUID dispute = jdbc.sql("select id from disputes where paypal_dispute_id = :p").param("p", disputeId)
                .query(UUID.class).single();
        jdbc.sql("""
                insert into drafts (id, dispute_id, version, status, recommendation, confidence, rationale, evidence,
                  notes, citations, missing_info, validation, llm_mode)
                values (:id, :d, 1, 'APPROVED', :rec, 0.9, 'r', '[]'::jsonb, 'We accept.', '[]'::jsonb, '[]'::jsonb,
                  '{"passed":false,"issues":[{"code":"REFUND_ALREADY_ISSUED","message":"x","path":"$"}]}'::jsonb,
                  'recorded')""")
                .param("id", UUID.fromString("00000000-0000-0000-0000-000000000005")).param("d", dispute)
                .param("rec", recommendation).update();
    }

    @Test
    void acceptClaimIsRefusedForARefundedTransactionEvenWhenApproved() throws Exception {
        approvedDraft("DEMO-D-1005", "ACCEPT");
        mvc.perform(post("/api/drafts/{id}/submit", "00000000-0000-0000-0000-000000000005")
                        .header("X-Admin-Token", "test-token").header("Idempotency-Key", "key-double-refund-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Refund already issued"));
        org.assertj.core.api.Assertions.assertThat(
                jdbc.sql("select count(*) from submissions").query(Integer.class).single()).isZero();
    }
}
