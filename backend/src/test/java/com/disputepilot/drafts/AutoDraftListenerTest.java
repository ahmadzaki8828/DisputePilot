package com.disputepilot.drafts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmClient;
import com.disputepilot.domain.DisputeChangedEvent;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A dispute that lands actionable gets a draft without anyone asking (plan §13 B step 6). */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.drafts.auto=true"})
@AutoConfigureMockMvc
class AutoDraftListenerTest {

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

    /** The change types really published: the PayPal event type, or RECONCILED / MANUAL_SYNC. */
    private static final String CREATED = "CUSTOMER.DISPUTE.CREATED";
    private static final String UPDATED = "CUSTOMER.DISPUTE.UPDATED";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEventPublisher events;
    @Autowired DraftRepository drafts;

    @BeforeEach
    void seed() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        mvc.perform(post("/api/dev/seed").header("X-Admin-Token", "test-token")).andExpect(status().isOk());
    }

    private UUID disputeId(String paypalDisputeId) {
        return jdbc.sql("select id from disputes where paypal_dispute_id = :pid")
                .param("pid", paypalDisputeId).query(UUID.class).single();
    }

    @Test
    void anActionableDisputeIsDraftedOnTheChangeEvent() {
        UUID id = disputeId("DEMO-D-1001");

        events.publishEvent(new DisputeChangedEvent(id, CREATED));

        assertThat(drafts.findLatest(id)).isPresent();
        assertThat(drafts.findLatest(id).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void aSecondEventDoesNotDraftAgain() {
        UUID id = disputeId("DEMO-D-1001");

        events.publishEvent(new DisputeChangedEvent(id, CREATED));
        events.publishEvent(new DisputeChangedEvent(id, UPDATED));

        assertThat(drafts.findLatest(id).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void aDisputeTheSellerCannotActOnIsNotDrafted() {
        UUID id = disputeId("DEMO-D-1001");
        jdbc.sql("update disputes set status = 'UNDER_REVIEW', allowed_actions = '{}' where id = :id")
                .param("id", id).update();

        events.publishEvent(new DisputeChangedEvent(id, UPDATED));

        assertThat(drafts.findLatest(id)).isEmpty();
    }

    @Test
    void anUnknownDisputeIsIgnoredRatherThanBreakingIngestion() {
        events.publishEvent(new DisputeChangedEvent(UUID.randomUUID(), CREATED));
        assertThat(jdbc.sql("select count(*) from drafts").query(Integer.class).single()).isZero();
    }
}
