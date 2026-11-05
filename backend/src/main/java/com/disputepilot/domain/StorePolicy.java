package com.disputepilot.domain;

/**
 * The store's published policy. {@code returnAddress} is the store's own address for returns (not buyer data);
 * PayPal needs it with a {@code REFUND_WITH_RETURN} offer. It is null when the store takes no returns.
 */
public record StorePolicy(
        String merchantName,
        String returns,
        String shipping,
        String refunds,
        String productDescriptions,
        String contact,
        Order.Address returnAddress) {
}
