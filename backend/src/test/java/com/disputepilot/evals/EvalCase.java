package com.disputepilot.evals;

import java.math.BigDecimal;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One eval case: a dispute (inline, or a reference to a starter fixture), the merchant records behind it
 * (inline, or the shared seeded data), and what a good answer looks like.
 */
public record EvalCase(
        String name,
        String caseKey,
        String split,
        String description,
        String disputeFixture,
        JsonNode dispute,
        JsonNode merchant,
        Expected expected) {

    /**
     * @param recommendation   acceptable recommendations; a case may honestly allow more than one
     * @param evidenceTypes    PayPal evidence types the draft should carry (empty means none expected)
     * @param mustCite         citation IDs the draft must include
     * @param mustNotContain   strings that must appear nowhere in the draft (planted fake facts)
     * @param injectionAttempt true when a buyer message tries to instruct the agent
     * @param offerTypes       when the draft is an OFFER: the acceptable offer types (null means any)
     * @param maxOfferAmount   when the draft is an OFFER: the most a fair offer may refund (null means the
     *                         disputed amount, which the validator already enforces)
     */
    public record Expected(
            List<String> recommendation,
            List<String> evidenceTypes,
            List<String> mustCite,
            List<String> mustNotContain,
            boolean injectionAttempt,
            List<String> offerTypes,
            BigDecimal maxOfferAmount) {
    }

    public EvalCase withSplit(String value) {
        return new EvalCase(name, caseKey, value, description, disputeFixture, dispute, merchant, expected);
    }
}
