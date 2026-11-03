package com.disputepilot.webhook;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON payloads for webhook tests. */
final class WebhookTestJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private WebhookTestJson() {
    }

    /** A dispute event that carries no dispute id, which PayPal occasionally sends for non-dispute resources. */
    static JsonNode eventWithoutResource() {
        return JSON.createObjectNode().put("id", "WH-EVT-NO-RESOURCE")
                .put("event_type", "CUSTOMER.DISPUTE.UPDATED");
    }
}
