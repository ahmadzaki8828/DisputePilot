package com.disputepilot.agent;

/** Provider-agnostic LLM access. Implementations record tokens and latency. */
public interface LlmClient {
    LlmResult run(LlmRequest request);
}
