package com.disputepilot.paypal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The real PayPal Disputes API (sandbox only - see {@code SandboxGuard}). Implements plan §8.1-8.3:
 * cached OAuth token, bounded timeouts on the {@link RestClient}, the retry rules in
 * {@link PayPalRetryPolicy}, PayPal error JSON mapped to {@link PayPalApiException} with its {@code debug_id},
 * and {@code provide-evidence} sent as multipart with a JSON part named {@code input}.
 */
public class HttpPayPalDisputesClient implements PayPalDisputesClient {

    private static final Logger log = LoggerFactory.getLogger(HttpPayPalDisputesClient.class);
    private static final String DISPUTES = "/v1/customer/disputes";

    private final RestClient rest;
    private final PayPalTokenProvider tokens;
    private final JsonMapper json;
    private final PayPalDisputeMapper mapper;
    private final PayPalRetryPolicy retry;
    private final String webhookId;

    public HttpPayPalDisputesClient(RestClient rest, PayPalTokenProvider tokens, JsonMapper json,
            PayPalDisputeMapper mapper, PayPalRetryPolicy retry, String webhookId) {
        this.rest = rest;
        this.tokens = tokens;
        this.json = json;
        this.mapper = mapper;
        this.retry = retry;
        this.webhookId = webhookId == null ? "" : webhookId;
    }

    @Override
    public DisputePage listDisputes(DisputeQuery query) {
        DisputeQuery q = query == null ? DisputeQuery.all() : query;
        JsonNode body = get("listDisputes", uri -> {
            UriBuilder b = uri.path(DISPUTES);
            if (q.status() != null && !q.status().isBlank()) {
                b.queryParam("dispute_state", q.status());
            }
            if (q.updatedAfter() != null) {
                b.queryParam("update_time_after", q.updatedAfter().toString());
            }
            if (q.pageSize() != null) {
                b.queryParam("page_size", q.pageSize());
            }
            if (q.nextPageToken() != null && !q.nextPageToken().isBlank()) {
                b.queryParam("next_page_token", q.nextPageToken());
            }
            return b.build();
        });
        List<PayPalDispute> items = new ArrayList<>();
        body.path("items").forEach(item -> items.add(mapper.toDispute(item)));
        return new DisputePage(List.copyOf(items), nextPageToken(body));
    }

    @Override
    public PayPalDispute getDispute(String disputeId) {
        JsonNode body = get("getDispute", uri -> uri.path(DISPUTES + "/{id}").build(disputeId));
        return mapper.toDispute(body);
    }

    @Override
    public void provideEvidence(String disputeId, EvidenceSubmission s) {
        if (s == null || s.evidences() == null || s.evidences().isEmpty()) {
            throw new PayPalApiException(400, "INVALID_REQUEST",
                    "provide-evidence needs at least one evidence item.", null, List.of("MISSING_REQUIRED_PARAMETER"));
        }
        // The JSON part is named "input"; documents ride along as file parts (plan §8.2).
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("input", jsonPart(Map.of("evidences", s.evidences())));
        List<EvidenceSubmission.FilePart> files = s.files() == null ? List.of() : s.files();
        for (int i = 0; i < files.size(); i++) {
            parts.add("file" + (i + 1), filePart(files.get(i)));
        }
        action("provideEvidence", disputeId, "provide-evidence", parts, MediaType.MULTIPART_FORM_DATA);
    }

    @Override
    public void acceptClaim(String disputeId, AcceptClaimRequest r) {
        ObjectNode body = json.createObjectNode();
        if (r != null && r.note() != null) {
            body.put("note", r.note());
        }
        if (r != null && r.acceptClaimReason() != null) {
            body.put("accept_claim_reason", r.acceptClaimReason());
        }
        action("acceptClaim", disputeId, "accept-claim", body, MediaType.APPLICATION_JSON);
    }

    @Override
    public void makeOffer(String disputeId, OfferRequest r) {
        ObjectNode body = json.createObjectNode();
        if (r != null && r.note() != null) {
            body.put("note", r.note());
        }
        if (r != null && r.offerAmount() != null) {
            ObjectNode amount = body.putObject("offer_amount");
            amount.put("currency_code", r.offerAmount().currency());
            amount.put("value", r.offerAmount().value().toPlainString());
        }
        if (r != null && r.invoiceId() != null) {
            body.put("invoice_id", r.invoiceId());
        }
        body.put("offer_type", "REFUND");
        action("makeOffer", disputeId, "make-offer", body, MediaType.APPLICATION_JSON);
    }

    @Override
    public void sendMessage(String disputeId, String message) {
        ObjectNode body = json.createObjectNode();
        body.put("message", message);
        action("sendMessage", disputeId, "send-message", body, MediaType.APPLICATION_JSON);
    }

    @Override
    public void requireEvidence(String disputeId, String action) {
        ObjectNode body = json.createObjectNode();
        body.put("action", action);
        action("requireEvidence", disputeId, "require-evidence", body, MediaType.APPLICATION_JSON);
    }

    @Override
    public void adjudicate(String disputeId, String outcome) {
        ObjectNode body = json.createObjectNode();
        body.put("adjudication_outcome", outcome);
        action("adjudicate", disputeId, "adjudicate", body, MediaType.APPLICATION_JSON);
    }

    @Override
    public WebhookVerification verifyWebhook(WebhookHeaders h, String rawBody) {
        if (webhookId.isBlank()) {
            log.warn("PAYPAL_WEBHOOK_ID is not set; cannot verify webhook signatures");
            return new WebhookVerification(false, "FAILED");
        }
        ObjectNode body = json.createObjectNode();
        body.put("auth_algo", h == null ? null : h.authAlgo());
        body.put("cert_url", h == null ? null : h.certUrl());
        body.put("transmission_id", h == null ? null : h.transmissionId());
        body.put("transmission_sig", h == null ? null : h.transmissionSig());
        body.put("transmission_time", h == null ? null : h.transmissionTime());
        body.put("webhook_id", webhookId);
        try {
            body.set("webhook_event", json.readTree(rawBody == null ? "{}" : rawBody));
        } catch (RuntimeException e) {
            log.warn("Webhook body is not JSON; refusing verification");
            return new WebhookVerification(false, "FAILED");
        }
        try {
            JsonNode response = send("verifyWebhook", HttpMethod.POST,
                    uri -> uri.path("/v1/notifications/verify-webhook-signature").build(),
                    body, MediaType.APPLICATION_JSON, true);
            boolean verified = "SUCCESS".equals(response.path("verification_status").asString(null));
            return new WebhookVerification(verified, verified ? "VERIFIED" : "FAILED");
        } catch (PayPalApiException e) {
            log.warn("Webhook signature verification call failed: {}", e);
            return new WebhookVerification(false, "FAILED");
        }
    }

    // ---- internals ----

    private HttpEntity<String> jsonPart(Object value) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json.writeValueAsString(value), headers);
    }

    private HttpEntity<ByteArrayResource> filePart(EvidenceSubmission.FilePart file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(file.contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(file.contentType()));
        headers.setContentDisposition(ContentDisposition.formData()
                .name("file1").filename(file.filename()).build());
        return new HttpEntity<>(new ByteArrayResource(file.content()), headers);
    }

    private JsonNode get(String what, java.util.function.Function<UriBuilder, java.net.URI> uri) {
        return send(what, HttpMethod.GET, uri, null, null, true);
    }

    /** A POST action on one dispute. Not idempotent, so retries are limited (see {@link PayPalRetryPolicy}). */
    private void action(String what, String disputeId, String path, Object body, MediaType contentType) {
        send(what, HttpMethod.POST, uri -> uri.path(DISPUTES + "/{id}/" + path).build(disputeId),
                body, contentType, false);
    }

    private JsonNode send(String what, HttpMethod method, java.util.function.Function<UriBuilder, java.net.URI> uri,
            Object body, MediaType contentType, boolean idempotent) {
        return retry.execute(what, idempotent, () -> attempt(what, method, uri, body, contentType));
    }

    /** The raw outcome of one exchange, so status handling stays outside the response callback. */
    private record RawResponse(int status, String text) {
    }

    /** One logical attempt. A 401 drops the cached token and is re-sent once with a fresh one. */
    private JsonNode attempt(String what, HttpMethod method, java.util.function.Function<UriBuilder, java.net.URI> uri,
            Object body, MediaType contentType) {
        RawResponse r = exchange(what, method, uri, body, contentType);
        if (r.status() == 401) {
            // The token expired early or was revoked; a 401 never applied the request, so re-sending is safe.
            log.info("PayPal {} returned 401; refreshing the access token and retrying once", what);
            tokens.invalidate();
            r = exchange(what, method, uri, body, contentType);
        }
        if (r.status() >= 400) {
            PayPalApiException e = PayPalApiException.fromResponse(r.status(), r.text(), json);
            log.warn("PayPal {} -> {} {} debug_id={} issues={}", what, r.status(), e.name(), e.debugId(), e.issues());
            throw e;
        }
        return r.text().isBlank() ? json.createObjectNode() : json.readTree(r.text());
    }

    private RawResponse exchange(String what, HttpMethod method,
            java.util.function.Function<UriBuilder, java.net.URI> uri, Object body, MediaType contentType) {
        try {
            RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken())
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.contentType(contentType).body(body);
            }
            return spec.exchange((request, response) -> new RawResponse(response.getStatusCode().value(),
                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (ResourceAccessException e) {
            throw PayPalApiException.transport(what, e);
        }
    }

    private static String nextPageToken(JsonNode listResponse) {
        for (JsonNode link : listResponse.path("links")) {
            if ("next".equals(link.path("rel").asString(null))) {
                String href = link.path("href").asString("");
                int at = href.indexOf("next_page_token=");
                if (at >= 0) {
                    String tail = href.substring(at + "next_page_token=".length());
                    int amp = tail.indexOf('&');
                    return amp < 0 ? tail : tail.substring(0, amp);
                }
            }
        }
        return null;
    }
}
