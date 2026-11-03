package com.disputepilot.domain;

import java.time.Instant;
import java.util.List;

/** Merchant-side order (synthetic, "Maple & Thread Co."). */
public record Order(
        String id,
        String paypalTransactionId,
        Instant placedAt,
        String buyerName,
        String buyerEmail,
        String buyerPhone,
        Address shippingAddress,
        List<Item> items,
        Money total,
        String status,
        List<Refund> refunds) {

    public record Address(String line1, String city, String region, String postalCode, String country) {
    }

    public record Item(String sku, String name, int quantity, Money unitPrice) {
    }

    public record Refund(String id, Money amount, Instant issuedAt, String reason) {
    }
}
