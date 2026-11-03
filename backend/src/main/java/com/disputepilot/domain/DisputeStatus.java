package com.disputepilot.domain;

public enum DisputeStatus {
    OPEN,
    WAITING_FOR_BUYER_RESPONSE,
    WAITING_FOR_SELLER_RESPONSE,
    UNDER_REVIEW,
    RESOLVED,
    OTHER;

    public static DisputeStatus fromPaypal(String value) {
        if (value != null) {
            for (DisputeStatus s : values()) {
                if (s.name().equalsIgnoreCase(value)) {
                    return s;
                }
            }
        }
        return OTHER;
    }
}
