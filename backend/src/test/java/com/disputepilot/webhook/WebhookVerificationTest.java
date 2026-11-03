package com.disputepilot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.paypal.PayPalDisputesClient;
import com.disputepilot.paypal.WebhookHeaders;
import com.disputepilot.paypal.WebhookVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Signature verification in sandbox mode (plan §8.2). The PayPal client is mocked, so no call leaves the JVM.
 * A delivery that does not verify is stored as FAILED and must never be processed - not even when its payload
 * claims to be about a simulated DEMO- dispute.
 */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=sandbox",
        "dp.paypal.webhook-id=WH-TEST-ID"})
@AutoConfigureMockMvc
class WebhookVerificationTest {

    private static final String EVENT = """
            {"id":"WH-EVT-REAL-1","event_type":"CUSTOMER.DISPUTE.CREATED",
             "resource":{"dispute_id":"PP-D-55001","status":"WAITING_FOR_SELLER_RESPONSE"}}""";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired WebhookEventRepository events;
    @Autowired DisputeOutboxWorker worker;
    @MockitoBean PayPalDisputesClient paypal;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
    }

    private String verificationColumn() {
        return jdbc.sql("select verification from webhook_events").query(String.class).single();
    }

    @Test
    void aVerifiedDeliveryIsStoredAndProcessable() throws Exception {
        when(paypal.verifyWebhook(any(), any())).thenReturn(new WebhookVerification(true, "VERIFIED"));

        mvc.perform(post("/webhooks/paypal")
                        .header("PAYPAL-AUTH-ALGO", "SHA256withRSA")
                        .header("PAYPAL-CERT-URL", "https://api.sandbox.paypal.com/cert.pem")
                        .header("PAYPAL-TRANSMISSION-ID", "trans-1")
                        .header("PAYPAL-TRANSMISSION-SIG", "sig")
                        .header("PAYPAL-TRANSMISSION-TIME", "2026-11-03T12:00:00Z")
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stored"))
                .andExpect(jsonPath("$.verification").value("VERIFIED"));

        assertThat(verificationColumn()).isEqualTo(WebhookEvent.VERIFIED);
        assertThat(events.claimBatch(10, 5)).hasSize(1);
        // The headers PayPal sent are the ones we asked it to verify.
        verify(paypal).verifyWebhook(eq(new WebhookHeaders("SHA256withRSA",
                "https://api.sandbox.paypal.com/cert.pem", "trans-1", "sig", "2026-11-03T12:00:00Z")), any());
    }

    @Test
    void anUnverifiedDeliveryIsStoredAsFailedAndNeverProcessed() throws Exception {
        when(paypal.verifyWebhook(any(), any())).thenReturn(new WebhookVerification(false, "FAILED"));

        // Still a 200: PayPal should stop retrying a delivery we will not act on.
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.verification").value("FAILED"));

        assertThat(verificationColumn()).isEqualTo(WebhookEvent.FAILED);
        assertThat(events.claimBatch(10, 5)).isEmpty();
        assertThat(worker.processPending()).isZero();
        verify(paypal, never()).getDispute(any());
        assertThat(jdbc.sql("select count(*) from disputes").query(Integer.class).single()).isZero();
    }

    @Test
    void aDeliveryClaimingToBeSimulatedIsStillVerified() throws Exception {
        when(paypal.verifyWebhook(any(), any())).thenReturn(new WebhookVerification(false, "FAILED"));
        String spoofed = """
                {"id":"WH-EVT-SPOOF","event_type":"CUSTOMER.DISPUTE.RESOLVED",
                 "resource":{"dispute_id":"DEMO-D-1001"}}""";

        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(spoofed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verification").value("FAILED"));

        assertThat(verificationColumn()).isEqualTo(WebhookEvent.FAILED);
        verify(paypal).verifyWebhook(any(), any());
        assertThat(events.claimBatch(10, 5)).isEmpty();
    }

    @Test
    void aDuplicateIsNotVerifiedTwiceIntoASecondRow() throws Exception {
        when(paypal.verifyWebhook(any(), any())).thenReturn(new WebhookVerification(true, "VERIFIED"));

        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("stored"));
        mvc.perform(post("/webhooks/paypal").contentType(MediaType.APPLICATION_JSON).content(EVENT))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("duplicate"));

        assertThat(events.countByEventId("WH-EVT-REAL-1")).isEqualTo(1);
    }
}
