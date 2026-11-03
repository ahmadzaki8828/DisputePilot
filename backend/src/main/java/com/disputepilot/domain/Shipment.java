package com.disputepilot.domain;

import java.time.Instant;
import java.util.List;

public record Shipment(
        String id,
        String orderId,
        String carrier,
        String trackingNumber,
        String status,
        Instant shippedAt,
        Instant deliveredAt,
        String signedBy,
        String deliveredToCity,
        List<Event> events) {

    public record Event(Instant at, String description) {
    }
}
