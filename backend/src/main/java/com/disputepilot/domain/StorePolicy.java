package com.disputepilot.domain;

public record StorePolicy(
        String merchantName,
        String returns,
        String shipping,
        String refunds,
        String productDescriptions,
        String contact) {
}
