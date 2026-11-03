package com.disputepilot.agent;

import com.disputepilot.domain.LlmMode;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * One recorded model conversation for a case, replayed by {@link RecordedLlmClient} so the whole demo works
 * with no API key. Every recording names the provider and model that produced it, and says whether a real
 * model produced it ({@code source: "live"}) or it was hand-written ({@code source: "sample"}).
 */
public record Recording(
        String caseKey,
        String source,
        String provider,
        String model,
        String promptVersion,
        String recordedAt,
        String note,
        List<Turn> turns) {

        public record Turn(
            String text,
            List<RecordedToolCall> toolCalls,
            int inputTokens,
            int outputTokens,
            long latencyMs) {
    }

        public record RecordedToolCall(String id, String name, JsonNode input) {
    }

    /** A replayed real response is RECORDED; a hand-written stand-in is SAMPLE, and labelled as such. */
    public LlmMode mode() {
        return "sample".equalsIgnoreCase(source) ? LlmMode.SAMPLE : LlmMode.RECORDED;
    }
}
