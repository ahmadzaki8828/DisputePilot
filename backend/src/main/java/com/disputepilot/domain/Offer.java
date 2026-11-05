package com.disputepilot.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * A settlement the merchant proposes through PayPal's make-offer call: an amount (in the dispute's currency) and
 * one of PayPal's offer types. {@code REPLACEMENT_WITHOUT_REFUND} sends no money, so its amount may be absent.
 */
public record Offer(Money amount, String offerType) {

    public static final String REFUND = "REFUND";
    public static final String REFUND_WITH_RETURN = "REFUND_WITH_RETURN";
    public static final String REFUND_WITH_REPLACEMENT = "REFUND_WITH_REPLACEMENT";
    public static final String REPLACEMENT_WITHOUT_REFUND = "REPLACEMENT_WITHOUT_REFUND";

    /** PayPal's make-offer {@code offer_type} values. */
    public static final List<String> TYPES =
            List.of(REFUND, REFUND_WITH_RETURN, REFUND_WITH_REPLACEMENT, REPLACEMENT_WITHOUT_REFUND);

    public static boolean isKnownType(String type) {
        return type != null && TYPES.contains(type);
    }

    /** True when the offer sends money back, so it needs an amount above zero. */
    @JsonIgnore
    public boolean refundsMoney() {
        return !REPLACEMENT_WITHOUT_REFUND.equals(offerType);
    }

    /** True when PayPal needs the store's return address with the offer. */
    @JsonIgnore
    public boolean needsReturnAddress() {
        return REFUND_WITH_RETURN.equals(offerType);
    }

    public Offer withCurrencyIfMissing(String currency) {
        if (amount == null || amount.currency() != null || currency == null) {
            return this;
        }
        return new Offer(new Money(currency, amount.value()), offerType);
    }
}
