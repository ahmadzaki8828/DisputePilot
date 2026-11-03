package com.disputepilot.paypal;

import java.time.Instant;

/** Filters for GET /v1/customer/disputes. All fields optional. */
public record DisputeQuery(String status, Instant updatedAfter, Integer pageSize, String nextPageToken) {
    public static DisputeQuery all() {
        return new DisputeQuery(null, null, null, null);
    }
}
