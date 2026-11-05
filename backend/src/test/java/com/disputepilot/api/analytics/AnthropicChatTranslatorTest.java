package com.disputepilot.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnthropicChatTranslatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void chatRequestBecomesAMessagesRequest() {
        JsonNode chat = JSON.readTree("""
                {"messages":[
                  {"role":"system","content":"You edit dashboards."},
                  {"role":"user","content":[{"type":"text","text":"Add a grid"}]},
                  {"role":"assistant","content":"","tool_calls":[{"id":"t1","type":"function",
                    "function":{"name":"add_widget","arguments":"{\\"type\\":\\"grid\\"}"}}]},
                  {"role":"tool","tool_call_id":"t1","content":"ok"},
                  {"role":"tool","tool_call_id":"t2","content":"ok too"}],
                 "tools":[{"type":"function","function":{"name":"add_widget","description":"Adds",
                   "parameters":{"type":"object","properties":{"type":{"type":"string"}}}}}],
                 "tool_choice":"required",
                 "response_format":{"type":"json_schema","json_schema":{"name":"x","schema":{"type":"object"}}}}""");
        JsonNode out = AnthropicChatTranslator.toMessages(chat, "claude-haiku-5-5", 4096, JSON);

        assertThat(out.path("model").asString()).isEqualTo("claude-haiku-5-5");
        assertThat(out.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(out.path("system").asString()).startsWith("You edit dashboards.").contains("JSON schema");
        assertThat(out.path("messages")).hasSize(3);
        assertThat(out.path("messages").path(1).path("content").path(0).path("type").asString()).isEqualTo("tool_use");
        assertThat(out.path("messages").path(1).path("content").path(0).path("input").path("type").asString())
                .isEqualTo("grid");
        // Both tool results land in one user turn.
        assertThat(out.path("messages").path(2).path("content")).hasSize(2);
        assertThat(out.path("tools").path(0).path("input_schema").path("type").asString()).isEqualTo("object");
        assertThat(out.path("tool_choice").path("type").asString()).isEqualTo("any");
    }

    @Test
    void messagesResponseBecomesAChatCompletion() {
        JsonNode msg = JSON.readTree("""
                {"id":"m1","model":"claude-haiku-5-5","stop_reason":"tool_use",
                 "content":[{"type":"text","text":"Adding it."},
                            {"type":"tool_use","id":"t9","name":"add_widget","input":{"type":"grid"}}],
                 "usage":{"input_tokens":10,"output_tokens":5}}""");
        JsonNode out = AnthropicChatTranslator.toChatCompletion(msg, JSON);

        JsonNode choice = out.path("choices").path(0);
        assertThat(choice.path("finish_reason").asString()).isEqualTo("tool_calls");
        assertThat(choice.path("message").path("content").asString()).isEqualTo("Adding it.");
        assertThat(choice.path("message").path("tool_calls").path(0).path("function").path("arguments").asString())
                .isEqualTo("{\"type\":\"grid\"}");
        assertThat(out.path("usage").path("total_tokens").asInt()).isEqualTo(15);
    }

    @Test
    void deadlineBucketsAndOutcomes() {
        assertThat(AnalyticsRepository.dueBucket(true, -1.0)).isEqualTo("Overdue");
        assertThat(AnalyticsRepository.dueBucket(true, 47.9)).isEqualTo("Under 48h");
        assertThat(AnalyticsRepository.dueBucket(true, 100.0)).isEqualTo("2-7 days");
        assertThat(AnalyticsRepository.dueBucket(true, 200.0)).isEqualTo("Over 7 days");
        assertThat(AnalyticsRepository.dueBucket(true, null)).isEqualTo("No deadline");
        assertThat(AnalyticsRepository.dueBucket(false, 10.0)).isEqualTo("Closed");
        assertThat(AnalyticsRepository.outcomeLabel("RESOLVED_SELLER_FAVOUR")).isEqualTo("Won");
        assertThat(AnalyticsRepository.outcomeLabel("RESOLVED_BUYER_FAVOUR")).isEqualTo("Lost");
        assertThat(AnalyticsRepository.outcomeLabel(null)).isEqualTo("Pending");
    }
}
