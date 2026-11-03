package com.disputepilot.paypal;

/** All PayPal Disputes API access goes through this interface (simulated, real sandbox, or routing). */
public interface PayPalDisputesClient {
    DisputePage listDisputes(DisputeQuery query);                    // GET  /v1/customer/disputes

    PayPalDispute getDispute(String disputeId);                      // GET  /v1/customer/disputes/{id}

    void provideEvidence(String disputeId, EvidenceSubmission s);    // POST .../provide-evidence (multipart)

    void acceptClaim(String disputeId, AcceptClaimRequest r);        // POST .../accept-claim

    void makeOffer(String disputeId, OfferRequest r);                // POST .../make-offer

    void sendMessage(String disputeId, String message);              // POST .../send-message

    // sandbox-only helpers
    void requireEvidence(String disputeId, String action);           // POST .../require-evidence {"action":"SELLER_EVIDENCE"}

    void adjudicate(String disputeId, String outcome);               // POST .../adjudicate {"adjudication_outcome":"SELLER_FAVOR"}

    WebhookVerification verifyWebhook(WebhookHeaders h, String rawBody); // POST /v1/notifications/verify-webhook-signature
}
