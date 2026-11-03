package com.disputepilot.paypal;

import java.util.List;

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

    public int httpStatus() { return httpStatus; }
    public String name() { return name; }
    public String debugId() { return debugId; }
    public List<String> issues() { return issues; }
}
