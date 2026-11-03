package com.disputepilot.domain;

public enum LlmMode {
    /** The provider was called for this draft. */
    LIVE,
    /** A real provider response, recorded earlier under evals/recordings/, was replayed. */
    RECORDED,
    /** A hand-written stand-in response was replayed - no model produced it. Labelled honestly in the UI. */
    SAMPLE;

    /** Lower-case value stored in drafts.llm_mode and shown in the UI. */
    public String dbValue() {
        return name().toLowerCase();
    }
}
