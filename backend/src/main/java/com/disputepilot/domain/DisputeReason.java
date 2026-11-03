package com.disputepilot.domain;

public enum DisputeReason {
    MERCHANDISE_OR_SERVICE_NOT_RECEIVED("Item not received"),
    MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED("Item not as described"),
    UNAUTHORISED("Unauthorised transaction"),
    CREDIT_NOT_PROCESSED("Credit not processed"),
    DUPLICATE_TRANSACTION("Duplicate transaction"),
    INCORRECT_AMOUNT("Incorrect amount"),
    OTHER("Other");

    private final String label;

    DisputeReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Lenient parse: unknown PayPal reasons map to OTHER. */
    public static DisputeReason fromPaypal(String value) {
        if (value != null) {
            for (DisputeReason r : values()) {
                if (r.name().equalsIgnoreCase(value)) {
                    return r;
                }
            }
        }
        return OTHER;
    }

    public static String labelFor(String value) {
        return fromPaypal(value).label;
    }
}
