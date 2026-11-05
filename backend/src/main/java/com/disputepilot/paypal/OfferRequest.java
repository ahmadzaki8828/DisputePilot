package com.disputepilot.paypal;

import com.disputepilot.domain.Money;
import com.disputepilot.domain.Offer;
import com.disputepilot.domain.Order;

/**
 * Body of PayPal's make-offer call. {@code offerType} is one of {@link Offer#TYPES} (REFUND when null);
 * {@code returnAddress} is the store's own address, required for {@code REFUND_WITH_RETURN}.
 */
public record OfferRequest(String note, Money offerAmount, String invoiceId, String offerType,
                           Order.Address returnAddress) {

    public OfferRequest(String note, Money offerAmount, String invoiceId) {
        this(note, offerAmount, invoiceId, Offer.REFUND, null);
    }

    public String effectiveOfferType() {
        return offerType == null || offerType.isBlank() ? Offer.REFUND : offerType;
    }
}
