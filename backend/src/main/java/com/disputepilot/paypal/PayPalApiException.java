package com.disputepilot.paypal;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A PayPal-shaped error: name, message, debug_id and details[].issue. */
public class PayPalApiException extends RuntimeException {
    private final int httpStatus;
    private final String name;
    private final String debugId;
    private final List<String> issues;

    public PayPalApiException(int httpStatus, String name, String message, String debugId, List<String> issues) {
        super(message);
        this.httpStatus = httpStatus;
        this.name = name;
        this.debugId = debugId;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public PayPalApiException(int httpStatus, String name, String message, String debugId, List<String> issues,
            Throwable cause) {
        this(httpStatus, name, message, debugId, issues);
        initCause(cause);
    }

    public int httpStatus() { return httpStatus; }
    public String name() { return name; }
    public String debugId() { return debugId; }
    public List<String> issues() { return issues; }

    /**
     * Parse a PayPal error response body. PayPal returns {@code {name, message, debug_id, details:[{issue}]}} for
     * API errors and {@code {error, error_description}} for OAuth errors; an empty or unparseable body still
     * yields a usable exception carrying the HTTP status.
     */
    public static PayPalApiException fromResponse(int httpStatus, String body, JsonMapper mapper) {
        String name = "PAYPAL_HTTP_" + httpStatus;
        String message = "PayPal returned HTTP " + httpStatus;
        String debugId = null;
        List<String> issues = new ArrayList<>();
        if (body != null && !body.isBlank()) {
            try {
                JsonNode n = mapper.readTree(body);
                name = first(n, "name", "error", name);
                message = first(n, "message", "error_description", message);
                String debug = n.path("debug_id").asString(null);
                debugId = debug == null || debug.isBlank() ? null : debug;
                n.path("details").forEach(d -> {
                    String issue = d.path("issue").asString(null);
                    if (issue != null && !issue.isBlank()) {
                        issues.add(issue);
                    }
                });
            } catch (JacksonException e) {
                message = "PayPal returned HTTP " + httpStatus + " with a non-JSON body";
            }
        }
        return new PayPalApiException(httpStatus, name, message, debugId, issues);
    }

    /** A transport-level failure (connection refused, read timeout, DNS): no HTTP status was ever received. */
    public static PayPalApiException transport(String what, Throwable cause) {
        return new PayPalApiException(0, "CONNECTION_ERROR",
                "Could not reach PayPal for " + what + ": " + cause.getMessage(), null, List.of(), cause);
    }

    /** True when PayPal signalled a condition that is worth another attempt: rate limiting, 5xx, or no response. */
    public boolean retryable() {
        return httpStatus == 0 || httpStatus == 429 || httpStatus >= 500;
    }

    /** True when the request certainly did not take effect, so even a non-idempotent POST may be retried. */
    public boolean definitelyNotApplied() {
        return httpStatus == 0 || httpStatus == 429;
    }

    private static String first(JsonNode n, String a, String b, String fallback) {
        String v = n.path(a).asString(null);
        if (v == null || v.isBlank()) {
            v = n.path(b).asString(null);
        }
        return v == null || v.isBlank() ? fallback : v;
    }

    @Override
    public String toString() {
        return "PayPalApiException[status=" + httpStatus + ", name=" + name + ", debug_id=" + debugId
                + ", issues=" + issues + ", message=" + getMessage() + "]";
    }
}
