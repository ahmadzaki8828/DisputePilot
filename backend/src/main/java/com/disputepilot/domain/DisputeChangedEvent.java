package com.disputepilot.domain;

import java.util.UUID;

/**
 * Published after a dispute row is created or updated from PayPal, so the agent can draft a response without
 * the ingestion path knowing anything about drafting.
 *
 * @param disputeId  the internal dispute id (disputes.id)
 * @param changeType CREATED, UPDATED or RESOLVED
 */
public record DisputeChangedEvent(UUID disputeId, String changeType) {

    public static final String CREATED = "CREATED";
    public static final String UPDATED = "UPDATED";
    public static final String RESOLVED = "RESOLVED";
}
