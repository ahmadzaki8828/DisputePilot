package com.disputepilot.evals;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One eval case: a dispute (inline, or a reference to a starter fixture), the merchant records behind it
 * (inline, or the shared seeded data), and what a good answer looks like.
 */
public record EvalCase(
        String name,
        String caseKey,
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
     */
    public record Expected(
            List<String> recommendation,
            List<String> evidenceTypes,
            List<String> mustCite,
            List<String> mustNotContain,
            boolean injectionAttempt) {
    }
}
