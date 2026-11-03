package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

    @Test
    void returnsCannedSubmitDraftToolCall() {
        FakeLlmClient fake = new FakeLlmClient();
        LlmResult r = fake.run(new LlmRequest("case-1", "v0", "system", List.of(), List.of(), "submit_draft", 0.0, 1024));
        assertThat(r.toolCalls()).hasSize(1);
        assertThat(r.toolCalls().get(0).name()).isEqualTo("submit_draft");
        assertThat(r.toolCalls().get(0).input().get("recommendation").asString()).isEqualTo("FIGHT");
        assertThat(r.toolCalls().get(0).input().get("citations")).isNotEmpty();
        assertThat(fake.callCount()).isEqualTo(1);
    }
}
