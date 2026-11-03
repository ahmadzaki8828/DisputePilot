package com.disputepilot.agent;

/** Provider-agnostic LLM access. Implementations record tokens and latency. */
public interface LlmClient {
    LlmResult run(LlmRequest request);

    /** True when a call goes to a real model provider (as opposed to replaying recorded or canned responses). */
    default boolean isLive() {
        return false;
    }

    /** Human-readable provider label, stored on every draft and shown in the UI. */
    default String providerName() {
        return getClass().getSimpleName();
    }
}
