package com.disputepilot.drafts;

import com.disputepilot.agent.DisputeAgent;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.RecordedLlmClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * A second agent bound to recorded responses. When the live provider is rate-limited or the daily cap is spent,
 * the draft service re-runs the whole draft here for disputes that have a recording. The resulting draft carries
 * the recording's own label (provider, model, mode "recorded" or "sample"), so the UI never passes a replay off
 * as a live answer.
 */
@Component
public class RecordedFallback {

    private final RecordedLlmClient recorded = new RecordedLlmClient();
    private final DisputeAgent agent;

    public RecordedFallback(DraftValidator validator, JsonMapper mapper) {
        this.agent = new DisputeAgent(recorded, validator, mapper);
    }

    public boolean covers(String caseKey) {
        return recorded.has(caseKey);
    }

    public DisputeAgent agent() {
        return agent;
    }
}
