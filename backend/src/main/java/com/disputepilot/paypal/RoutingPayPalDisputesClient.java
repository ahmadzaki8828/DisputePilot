package com.disputepilot.paypal;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends {@code DEMO-} dispute IDs to the simulated client and everything else to the real sandbox (plan §8.1),
 * so a judge's simulated dispute travels the same webhook -> outbox -> agent -> submit path as a real one.
 * {@link #listDisputes} merges both sources, simulated disputes first.
 */
public class RoutingPayPalDisputesClient implements PayPalDisputesClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingPayPalDisputesClient.class);

    private final SimulatedPayPalDisputesClient simulated;
    private final PayPalDisputesClient http;

    public RoutingPayPalDisputesClient(SimulatedPayPalDisputesClient simulated, PayPalDisputesClient http) {
        this.simulated = simulated;
        this.http = http;
    }

    /** The client that owns this dispute ID. */
    public PayPalDisputesClient routeFor(String disputeId) {
        return PayPalDisputeMapper.isSimulatedId(disputeId) ? simulated : http;
    }

    @Override
    public DisputePage listDisputes(DisputeQuery query) {
        List<PayPalDispute> items = new ArrayList<>(simulated.listDisputes(query).items());
        DisputePage real;
        try {
            real = http.listDisputes(query);
        } catch (PayPalApiException e) {
            // The simulated half of the inbox must stay usable even when the sandbox is unreachable.
            log.warn("Listing sandbox disputes failed; returning simulated disputes only: {}", e);
            return new DisputePage(List.copyOf(items), null);
        }
        items.addAll(real.items());
        return new DisputePage(List.copyOf(items), real.nextPageToken());
    }

    @Override
    public PayPalDispute getDispute(String disputeId) {
        return routeFor(disputeId).getDispute(disputeId);
    }

    @Override
    public void provideEvidence(String disputeId, EvidenceSubmission s) {
        routeFor(disputeId).provideEvidence(disputeId, s);
    }

    @Override
    public void acceptClaim(String disputeId, AcceptClaimRequest r) {
        routeFor(disputeId).acceptClaim(disputeId, r);
    }

    @Override
    public void makeOffer(String disputeId, OfferRequest r) {
        routeFor(disputeId).makeOffer(disputeId, r);
    }

    @Override
    public void sendMessage(String disputeId, String message) {
        routeFor(disputeId).sendMessage(disputeId, message);
    }

    @Override
    public void requireEvidence(String disputeId, String action) {
        routeFor(disputeId).requireEvidence(disputeId, action);
    }

    @Override
    public void adjudicate(String disputeId, String outcome) {
        routeFor(disputeId).adjudicate(disputeId, outcome);
    }

    /**
     * Signature verification always goes to PayPal: a delivery that claims to be about a {@code DEMO-} dispute
     * but arrived over the network is not trusted just because of its payload.
     */
    @Override
    public WebhookVerification verifyWebhook(WebhookHeaders h, String rawBody) {
        return http.verifyWebhook(h, rawBody);
    }
}
