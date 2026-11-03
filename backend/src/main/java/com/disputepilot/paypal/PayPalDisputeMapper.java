package com.disputepilot.paypal;

import com.disputepilot.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Maps a raw PayPal dispute-details payload to {@link PayPalDispute}: amount, status, the evidence PayPal has
 * requested from the seller ({@code evidences[]} with {@code source = REQUESTED_FROM_SELLER}), the seller
 * response deadline, and the actions PayPal currently allows, derived from {@code links[].rel} rather than from
 * a hard-coded state machine (plan §3).
 *
 * <p>The raw payload is kept verbatim on the record so nothing is lost on the way to the {@code disputes.raw}
 * column. Buyer-written message text is carried as data only; it is never treated as instructions.
 */
@Component
public class PayPalDisputeMapper {

    /** links[].rel as PayPal spells it -> the action name the rest of the app uses. */
    private static final Map<String, String> ACTION_BY_REL = Map.of(
            "provide_evidence", "PROVIDE_EVIDENCE",
            "provide-evidence", "PROVIDE_EVIDENCE",
            "accept_claim", "ACCEPT_CLAIM",
            "accept-claim", "ACCEPT_CLAIM",
            "make_offer", "MAKE_OFFER",
            "make-offer", "MAKE_OFFER",
            "send_message", "SEND_MESSAGE",
            "send-message", "SEND_MESSAGE");

    public PayPalDispute toDispute(JsonNode d) {
        String id = d.path("dispute_id").asString(null);
        JsonNode amount = d.path("dispute_amount");
        JsonNode outcome = d.path("dispute_outcome").path("outcome_code");
        return new PayPalDispute(
                id,
                isSimulatedId(id),
                d.path("reason").asString(null),
                d.path("status").asString(null),
                text(d, "dispute_life_cycle_stage"),
                money(amount),
                instant(text(d, "seller_response_due_date")),
                text(d.path("disputed_transactions").path(0), "seller_transaction_id"),
                requestedEvidence(d),
                allowedActions(d),
                outcome.isMissingNode() || outcome.isNull() ? null : outcome.asString(),
                instant(text(d, "update_time")),
                d.deepCopy());
    }

    /** Simulated disputes are the ones DisputePilot invents for the demo; their IDs start "DEMO-". */
    public static boolean isSimulatedId(String disputeId) {
        return disputeId != null && disputeId.startsWith(SimulatedPayPalDisputesClient.DEMO_PREFIX);
    }

    /** Evidence types PayPal asked the seller for; prefer these over the playbook's defaults (plan §8.2). */
    public static List<String> requestedEvidence(JsonNode d) {
        List<String> requested = new ArrayList<>();
        d.path("evidences").forEach(e -> {
            if ("REQUESTED_FROM_SELLER".equals(e.path("source").asString(null))) {
                String type = e.path("evidence_type").asString(null);
                if (type != null && !requested.contains(type)) {
                    requested.add(type);
                }
            }
        });
        return List.copyOf(requested);
    }

    /** What PayPal says we may do right now, from links[].rel. Order is stable and duplicates are dropped. */
    public static List<String> allowedActions(JsonNode d) {
        Map<String, Boolean> actions = new LinkedHashMap<>();
        d.path("links").forEach(l -> {
            String action = ACTION_BY_REL.get(l.path("rel").asString(""));
            if (action != null) {
                actions.put(action, Boolean.TRUE);
            }
        });
        return List.copyOf(actions.keySet());
    }

    private static Money money(JsonNode amount) {
        String value = amount.path("value").asString(null);
        String currency = amount.path("currency_code").asString(null);
        if (value == null || currency == null) {
            return new Money(currency == null ? "CAD" : currency, BigDecimal.ZERO);
        }
        return new Money(currency, new BigDecimal(value));
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asString(null);
        return v == null || v.isBlank() ? null : v;
    }

    private static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
