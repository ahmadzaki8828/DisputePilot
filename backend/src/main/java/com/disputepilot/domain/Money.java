package com.disputepilot.domain;

import java.math.BigDecimal;

/** Money as BigDecimal + ISO 4217 currency. */
public record Money(String currency, BigDecimal value) {
}
