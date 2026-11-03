package com.disputepilot.sync;

import java.util.UUID;

/**
 * Published (as a Spring application event) after a dispute row has been refreshed from PayPal. The agent
 * listens for this to decide whether a new draft is worth generating; nothing in the PayPal pipeline depends
 * on a listener existing.
 *
 * @param disputeId  the internal {@code disputes.id}
 * @param changeType why the dispute was refreshed, e.g. {@code CUSTOMER.DISPUTE.CREATED}, {@code RECONCILED}
 *                   or {@code MANUAL_SYNC}
 */
public record DisputeChangedEvent(UUID disputeId, String changeType) {
}
