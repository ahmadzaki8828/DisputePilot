package com.disputepilot.paypal;

/** The PAYPAL-* headers sent with a webhook delivery. */
public record WebhookHeaders(
        String authAlgo, String certUrl, String transmissionId, String transmissionSig, String transmissionTime) {
}
