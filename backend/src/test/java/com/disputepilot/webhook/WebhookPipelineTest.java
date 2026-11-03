package com.disputepilot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.EvidenceSubmission;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.sync.DisputeChangedEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * POST /webhooks/paypal -> webhook_events -> outbox worker -> disputes, in mock mode (no network at all).
 * Covers plan §13 A.4-A.5: store once, answer 200 fast, re-fetch the dispute before writing anything.
 */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock"})
@AutoConfigureMockMvc
class WebhookPipelineTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    /** Captures the events the sync service publishes, the way Session B's agent will consume them. */
    @TestConfiguration
    static class CapturingListener {
        final List<DisputeChangedEvent> captured = new ArrayList<>();

        @EventListener
        void on(DisputeChangedEvent event) {
            captured.add(event);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired WebhookEventRepository events;
    @Autowired WebhookIngestService ingest;
    @Autowired DisputeOutboxWorker worker;
    @Autowired SimulatedPayPalDisputesClient simulated;
    @Autowired CapturingListener listener;

    private String createdEvent;

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        simulated.resetToFixtures();
        listener.captured.clear();
        createdEvent = new String(new ClassPathResource("fixtures/paypal/webhooks/customer-dispute-created.json")
                .getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private int disputeCount() {
        return jdbc.sql("select count(*) from disputes").query(Integer.class).single();
    }

    @Test
    void aDeliveryIsStoredWithoutAnAdminTokenAndAnsweredImmediately() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.verification").value("SKIPPED_SIMULATED"));

        assertThat(events.countByEventId("WH-EVT-DEMO-0001")).isEqualTo(1);
        // Nothing is written to disputes by the endpoint itself: PayPal is re-read by the outbox worker.
        assertThat(disputeCount()).isZero();
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'WEBHOOK_RECEIVED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredTwiceLeavesOneRow() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("stored"));
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(events.countByEventId("WH-EVT-DEMO-0001")).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from webhook_events").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aMalformedDeliveryIsRejectedAsProblemJson() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed webhook"));
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.sql("select count(*) from webhook_events").query(Integer.class).single()).isZero();
    }

    @Test
    void theOutboxRefetchesTheDisputeAndPublishesAChangeEvent() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk());
        UUID eventId = UUID.fromString(jdbc.sql("select id from webhook_events").query(String.class).single());

        assertThat(worker.processPending()).isEqualTo(1);

        assertThat(events.state(eventId)).hasValueSatisfying(s -> {
            assertThat(s.processed()).isTrue();
            assertThat(s.attempts()).isEqualTo(1);
            assertThat(s.lastError()).isNull();
        });
        assertThat(events.countUnprocessed()).isZero();
        assertThat(disputeCount()).isEqualTo(1);

        // The row carries what PayPal says now, not what the webhook payload happened to contain.
        mvc.perform(get("/api/disputes").header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paypalDisputeId").value("DEMO-D-1001"))
                .andExpect(jsonPath("$[0].simulated").value(true))
                .andExpect(jsonPath("$[0].status").value("WAITING_FOR_SELLER_RESPONSE"));

        assertThat(listener.captured).hasSize(1);
        assertThat(listener.captured.get(0).changeType()).isEqualTo("CUSTOMER.DISPUTE.CREATED");
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'DISPUTE_SYNCED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void processingIsIdempotentSoARedeliveryChangesNothingTwice() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk());
        assertThat(worker.processPending()).isEqualTo(1);
        // A second run has nothing to claim, and the dispute is not duplicated.
        assertThat(worker.processPending()).isZero();
        assertThat(disputeCount()).isEqualTo(1);
    }

    @Test
    void anUpdateEventRefreshesTheStatusOfADisputeWeAlreadyHold() throws Exception {
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(createdEvent))
                .andExpect(status().isOk());
        worker.processPending();

        // The seller responds out-of-band, so PayPal now reports UNDER_REVIEW.
        simulated.provideEvidence("DEMO-D-1001", new EvidenceSubmission(
                List.of(new EvidenceItem("PROOF_OF_FULFILLMENT", null, "Delivered with signature"))));
        String updated = new String(new ClassPathResource("fixtures/paypal/webhooks/customer-dispute-updated.json")
                .getContentAsByteArray(), StandardCharsets.UTF_8);
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(updated))
                .andExpect(status().isOk());
        assertThat(worker.processPending()).isEqualTo(1);

        assertThat(disputeCount()).isEqualTo(1);
        assertThat(jdbc.sql("select status from disputes").query(String.class).single()).isEqualTo("UNDER_REVIEW");
        assertThat(listener.captured).extracting(DisputeChangedEvent::changeType)
                .containsExactly("CUSTOMER.DISPUTE.CREATED", "CUSTOMER.DISPUTE.UPDATED");
    }

    @Test
    void anEventForAnUnknownDisputeIsRetriedAndThenLeftAlone() {
        PayPalDispute ghost = simulated.listDisputes(DisputeQuery.all()).items().get(0);
        ingest.ingestSimulatedEvent("CUSTOMER.DISPUTE.UPDATED", ghost);
        UUID eventId = UUID.fromString(jdbc.sql("select id from webhook_events").query(String.class).single());
        // The dispute disappears from PayPal's side before the outbox gets to it.
        simulated.reset();
        jdbc.sql("update webhook_events set resource_id = 'DEMO-GONE' where id = :id").param("id", eventId)
                .update();

        for (int attempt = 1; attempt <= 5; attempt++) {
            events.clearRetryDelay(eventId);
            assertThat(worker.processPending()).isZero();
            assertThat(events.state(eventId)).hasValueSatisfying(s -> {
                assertThat(s.processed()).isFalse();
                assertThat(s.lastError()).contains("404").contains("RESOURCE_NOT_FOUND");
            });
        }

        // Five attempts used up: the event is no longer claimed, so one bad event cannot spin forever.
        assertThat(events.state(eventId)).hasValueSatisfying(s -> assertThat(s.attempts()).isEqualTo(5));
        events.clearRetryDelay(eventId);
        assertThat(events.claimBatch(10, 5)).isEmpty();
        assertThat(disputeCount()).isZero();
    }

    @Test
    void aFailedAttemptIsBackedOffBeforeTheNextOne() {
        PayPalDispute dispute = simulated.getDispute("DEMO-D-1002");
        ingest.ingestSimulatedEvent("CUSTOMER.DISPUTE.UPDATED", dispute);
        UUID eventId = UUID.fromString(jdbc.sql("select id from webhook_events").query(String.class).single());
        jdbc.sql("update webhook_events set resource_id = 'DEMO-GONE' where id = :id").param("id", eventId)
                .update();

        worker.processPending();

        assertThat(events.state(eventId)).hasValueSatisfying(s -> assertThat(s.nextAttemptAt()).isNotNull());
        // Still unprocessed, but not due yet, so the next run leaves it alone.
        assertThat(events.claimBatch(10, 5)).isEmpty();
        assertThat(events.countUnprocessed()).isEqualTo(1);
    }

    @Test
    void aSimulatedEventTravelsTheSamePipelineAsARealDelivery() {
        PayPalDispute created = simulated.createFromTemplate("DEMO-D-1005");

        WebhookIngestService.Ingested result = ingest.ingestSimulatedEvent("CUSTOMER.DISPUTE.CREATED", created);

        assertThat(result.outcome()).isEqualTo("stored");
        assertThat(result.verification()).isEqualTo(WebhookEvent.SKIPPED_SIMULATED);
        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(jdbc.sql("select paypal_dispute_id from disputes").query(String.class).single())
                .isEqualTo(created.paypalDisputeId());
        assertThat(jdbc.sql("select simulated from disputes").query(Boolean.class).single()).isTrue();
        assertThat(listener.captured).hasSize(1);
    }

    @Test
    void anEventWithNoDisputeIdIsNotRetriedForever() {
        events.store("WH-EVT-NO-RESOURCE", "CUSTOMER.DISPUTE.UPDATED", null, WebhookEvent.SKIPPED_SIMULATED,
                WebhookTestJson.eventWithoutResource());

        assertThat(worker.processPending()).isEqualTo(1);
        assertThat(events.countUnprocessed()).isZero();
        assertThat(disputeCount()).isZero();
    }
}
