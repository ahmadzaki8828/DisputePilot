package com.disputepilot.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.agent.RecordedLlmClient;
import com.disputepilot.agent.LlmRequest;
import com.disputepilot.common.limits.CappedLlmClient;
import com.disputepilot.common.limits.DemoLimits;
import com.disputepilot.common.limits.LlmDailyCap;
import com.disputepilot.agent.FakeLlmClient;
import com.disputepilot.agent.LlmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

class LimitsAndConfigTest {

    private static AppProperties.Llm llm(String provider, String model) {
        return new AppProperties.Llm("", "", model, 300, provider, "", 4, 1000);
    }

    @Test
    void theDefaultModelFollowsTheProvider() {
        assertThat(llm("openai-compatible", "").effectiveModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(llm("", "").effectiveModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(llm("anthropic", "").effectiveModel()).isEqualTo("claude-haiku-5-5");
        assertThat(llm("Anthropic", "  ").effectiveModel()).isEqualTo("claude-haiku-5-5");
    }

    @Test
    void anExplicitModelAlwaysWins() {
        assertThat(llm("anthropic", "claude-sonnet-5-5").effectiveModel()).isEqualTo("claude-sonnet-5-5");
        assertThat(llm("openai-compatible", "llama-3.3-70b").effectiveModel()).isEqualTo("llama-3.3-70b");
    }

    @Test
    void perVisitorBucketsAreIndependentAndFinite() {
        DemoLimits limits = new DemoLimits(new AppProperties("t", false, null, null,
                new AppProperties.Demo(2, 3, false), null));

        assertThat(limits.tryDraft("a")).isTrue();
        assertThat(limits.tryDraft("a")).isTrue();
        assertThat(limits.tryDraft("a")).isFalse();
        assertThat(limits.tryDraft("b")).isTrue();
        // Drafts and simulate/reset actions are separate allowances.
        assertThat(limits.tryAction("a")).isTrue();
        assertThat(limits.tryAction("a")).isTrue();
        assertThat(limits.tryAction("a")).isTrue();
        assertThat(limits.tryAction("a")).isFalse();
    }

    @Test
    void theDailyCapResetsAtUtcMidnight() {
        Instant[] now = {Instant.parse("2026-11-03T23:59:00Z")};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        LlmDailyCap cap = new LlmDailyCap(2, clock);

        assertThat(cap.tryAcquire()).isTrue();
        assertThat(cap.tryAcquire()).isTrue();
        assertThat(cap.tryAcquire()).isFalse();
        now[0] = Instant.parse("2026-11-04T00:00:01Z");
        assertThat(cap.tryAcquire()).isTrue();
    }

    @Test
    void overTheCapALiveCallFailsLikeARateLimitedProvider() {
        LlmDailyCap cap = new LlmDailyCap(0, Clock.systemUTC());
        CappedLlmClient capped = new CappedLlmClient(new FakeLlmClient(), cap);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> capped.run(
                        new LlmRequest("k", "v", "s", List.of(), List.of(), null, 0, 10)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.httpStatus()).isEqualTo(429));
    }

    @Test
    void theClientAddressIsTheForwardedVisitorWhenPresent() {
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("10.0.0.5");
        assertThat(ClientIp.of(direct)).isEqualTo("10.0.0.5");

        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setRemoteAddr("10.0.0.5");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.9, 10.1.1.1");
        assertThat(ClientIp.of(forwarded)).isEqualTo("203.0.113.9");
    }

    @Test
    void aCloneOfARecordedDisputeReplaysItsTemplateWithTheIdRewritten() {
        RecordedLlmClient recorded = new RecordedLlmClient();
        assertThat(recorded.has("DEMO-D-1001")).isTrue();
        assertThat(recorded.has("DEMO-D-1001@DEMO-D-9001")).isTrue();
        assertThat(recorded.has("PP-UNKNOWN")).isFalse();

        var request = new LlmRequest("DEMO-D-1001@DEMO-D-9001", "v", "s",
                List.of(new LlmRequest.Message("assistant", null, List.of(), List.of())), List.of(), null, 0, 10);
        // Turn 2 of the recording is the final submit_draft call.
        var result = recorded.run(request);
        String text = JsonMapper.builder().build().writeValueAsString(result.toolCalls());
        assertThat(text).contains("DEMO-D-9001").doesNotContain("DEMO-D-1001");
    }
}
