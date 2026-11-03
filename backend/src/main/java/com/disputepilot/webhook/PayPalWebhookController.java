package com.disputepilot.webhook;

import com.disputepilot.common.ApiException;
import com.disputepilot.paypal.WebhookHeaders;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/paypal} - PayPal's dispute webhook receiver.
 *
 * <p>No admin token: PayPal cannot send one, and the signature is what authenticates the delivery (the admin
 * filter only covers {@code /api/**}). The handler verifies, stores and returns 200 immediately; a delivery
 * that fails verification is still answered with 200 so PayPal stops retrying it, but it is stored as
 * {@code FAILED} and never processed.
 */
@RestController
public class PayPalWebhookController {

    private final WebhookIngestService ingest;

    public PayPalWebhookController(WebhookIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping(path = "/webhooks/paypal", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receive(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "PAYPAL-AUTH-ALGO", required = false) String authAlgo,
            @RequestHeader(value = "PAYPAL-CERT-URL", required = false) String certUrl,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-SIG", required = false) String transmissionSig,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime) {
        WebhookHeaders headers = new WebhookHeaders(authAlgo, certUrl, transmissionId, transmissionSig,
                transmissionTime);
        try {
            WebhookIngestService.Ingested result = ingest.ingest(rawBody, headers);
            return Map.of("status", result.outcome(), "verification", result.verification());
        } catch (WebhookIngestService.MalformedWebhookException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Malformed webhook", e.getMessage());
        }
    }
}
