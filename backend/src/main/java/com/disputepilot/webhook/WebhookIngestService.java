package com.disputepilot.webhook;

import com.disputepilot.common.AppProperties;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.PayPalDisputesClient;
import com.disputepilot.paypal.WebhookHeaders;
import com.disputepilot.paypal.WebhookVerification;
import com.disputepilot.common.audit.AuditLogWriter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stores PayPal dispute webhook deliveries. The HTTP endpoint does nothing but verify and store (plan §8.3):
 * the dispute itself is re-fetched later by {@link DisputeOutboxWorker}, so a slow PayPal call can never delay
 * the 200 that stops PayPal retrying.
 *
 * <p>Verification follows plan §8.2. In {@code PAYPAL_MODE=mock} there is no signing key and no network, so
 * deliveries are stored as {@code SKIPPED_SIMULATED}. In sandbox mode every delivery is checked against
 * PayPal's verify-webhook-signature endpoint, and one that does not verify is stored as {@code FAILED} and
 * never processed - including a delivery that claims to concern a simulated {@code DEMO-} dispute.
 */
@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final AppProperties props;
    private final PayPalDisputesClient paypal;
    private final WebhookEventRepository events;
    private final AuditLogWriter audit;
    private final JsonMapper json;

    public WebhookIngestService(AppProperties props, PayPalDisputesClient paypal, WebhookEventRepository events,
            AuditLogWriter audit, JsonMapper json) {
        this.props = props;
        this.paypal = paypal;
        this.events = events;
        this.audit = audit;
        this.json = json;
    }

    /** What happened to one delivery. {@code eventId} is empty for a duplicate. */
    public record Ingested(String outcome, String verification, Optional<UUID> eventId) {
        public static final String STORED = "stored";
        public static final String DUPLICATE = "duplicate";
        public static final String REJECTED = "rejected";
    }

    /** Thrown when a delivery is not usable at all; the controller turns this into a 400. */
    public static class MalformedWebhookException extends RuntimeException {
        MalformedWebhookException(String message) {
            super(message);
        }
    }

    /** Verify and store one delivery that arrived over HTTP. */
    public Ingested ingest(String rawBody, WebhookHeaders headers) {
        JsonNode event = parse(rawBody);
        String eventId = text(event, "id");
        String eventType = text(event, "event_type");
        if (eventId == null || eventType == null) {
            throw new MalformedWebhookException("A webhook event needs both an id and an event_type.");
        }
        String verification = verify(rawBody, headers);
        return store(event, eventId, eventType, resourceId(event), verification);
    }

    /**
     * Push an event DisputePilot generated itself into the same pipeline, for simulated {@code DEMO-} disputes
     * (plan §13 A.4). Nothing arrived over the network, so there is no signature to check and the row is stored
     * as {@code SKIPPED_SIMULATED}.
     *
     * <p>Exposed for the demo endpoints: a judge creating a simulated dispute should see it travel exactly the
     * route a real one takes.
     */
    public Ingested ingestSimulatedEvent(String eventType, PayPalDispute dispute) {
        if (dispute == null || dispute.paypalDisputeId() == null) {
            throw new MalformedWebhookException("A simulated event needs a dispute.");
        }
        ObjectNode event = json.createObjectNode();
        String eventId = "WH-SIM-" + UUID.randomUUID();
        event.put("id", eventId);
        event.put("event_version", "1.0");
        event.put("create_time", Instant.now().toString());
        event.put("resource_type", "dispute");
        event.put("event_type", eventType);
        event.put("summary", "Simulated " + eventType + " for " + dispute.paypalDisputeId());
        event.set("resource", dispute.raw() == null ? json.createObjectNode() : dispute.raw().deepCopy());
        event.put("simulated", true);
        return store(event, eventId, eventType, dispute.paypalDisputeId(), WebhookEvent.SKIPPED_SIMULATED);
    }

    private Ingested store(JsonNode event, String eventId, String eventType, String resourceId,
            String verification) {
        Optional<UUID> stored = events.store(eventId, eventType, resourceId, verification, event);
        if (stored.isEmpty()) {
            log.info("Webhook {} ({}) already stored; ignoring the repeat delivery", eventId, eventType);
            return new Ingested(Ingested.DUPLICATE, verification, Optional.empty());
        }
        audit.system(AuditLogWriter.WEBHOOK_RECEIVED, null, Map.of(
                "paypalEventId", eventId,
                "eventType", eventType,
                "resourceId", resourceId == null ? "" : resourceId,
                "verification", verification));
        if (WebhookEvent.FAILED.equals(verification)) {
            log.warn("Webhook {} ({}) failed signature verification; stored but not processed", eventId, eventType);
            return new Ingested(Ingested.REJECTED, verification, stored);
        }
        log.info("Stored webhook {} ({}) for {} [{}]", eventId, eventType, resourceId, verification);
        return new Ingested(Ingested.STORED, verification, stored);
    }

    private String verify(String rawBody, WebhookHeaders headers) {
        if (props.paypal().mode() == AppProperties.PaypalMode.MOCK) {
            // No signing key and no network in mock mode; the whole point is that it runs with zero keys.
            return WebhookEvent.SKIPPED_SIMULATED;
        }
        WebhookVerification v = paypal.verifyWebhook(headers, rawBody);
        return v.verified() ? WebhookEvent.VERIFIED : WebhookEvent.FAILED;
    }

    private JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new MalformedWebhookException("The webhook body is empty.");
        }
        try {
            JsonNode node = json.readTree(rawBody);
            if (!node.isObject()) {
                throw new MalformedWebhookException("The webhook body must be a JSON object.");
            }
            return node;
        } catch (JacksonException e) {
            throw new MalformedWebhookException("The webhook body is not valid JSON.");
        }
    }

    /** The dispute this event concerns. PayPal puts it on the resource as dispute_id (sometimes just id). */
    private static String resourceId(JsonNode event) {
        JsonNode resource = event.path("resource");
        String id = text(resource, "dispute_id");
        return id != null ? id : text(resource, "id");
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asString(null);
        return v == null || v.isBlank() ? null : v;
    }
}
