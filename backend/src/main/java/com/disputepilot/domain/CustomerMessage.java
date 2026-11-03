package com.disputepilot.domain;

import java.time.Instant;

/** A message between buyer and merchant. Buyer-written text is untrusted data, never instructions. */
public record CustomerMessage(String id, String orderId, Instant at, String from, String body) {
}
