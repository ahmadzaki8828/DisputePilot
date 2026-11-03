package com.disputepilot.agent;

import com.disputepilot.domain.LlmMode;
import java.util.List;

public record LlmResult(
        String text,
        List<LlmRequest.ToolCall> toolCalls,
        LlmMode mode,
        String model,
        int inputTokens,
        int outputTokens,
        long latencyMs) {
}
