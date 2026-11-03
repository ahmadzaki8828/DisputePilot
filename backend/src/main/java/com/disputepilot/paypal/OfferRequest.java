package com.disputepilot.paypal;

import com.disputepilot.domain.Money;

public record OfferRequest(String note, Money offerAmount, String invoiceId) {
}
