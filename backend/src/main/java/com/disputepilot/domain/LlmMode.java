package com.disputepilot.domain;

public enum LlmMode {
    LIVE, RECORDED;

    /** Lower-case value stored in drafts.llm_mode and shown in the UI. */
    public String dbValue() {
        return name().toLowerCase();
    }
}
