package com.disputepilot.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.LlmMode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The live OpenAI-compatible client (Groq) against a stubbed endpoint: never the real network. */
class OpenAiCompatibleLlmClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/openai/v1/chat/completions";

    private WireMockServer wiremock;

    @BeforeEach
    void start() {
        wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wiremock.start();
    }

    @AfterEach
    void stop() {
        wiremock.stop();
    }

    private OpenAiCompatibleLlmClient client() {
        return client(new RetryPolicy(4, 0, millis -> { }));
    }

    private OpenAiCompatibleLlmClient client(RetryPolicy retry) {
        return new OpenAiCompatibleLlmClient(wiremock.baseUrl() + "/openai/v1", "test-key",
                "openai/gpt-oss-120b", JSON, retry);
    }

    private static LlmRequest request(String forcedTool, List<LlmRequest.Message> messages) {
        JsonNode schema = JSON.readTree("""
                {"type":"object","properties":{"recommendation":{"type":"string"}},"required":["recommendation"]}""");
        return new LlmRequest("inr-delivered-signature", "v1", "You are a dispute analyst.", messages,
                List.of(new LlmRequest.ToolDefinition("submit_draft", "Submit the final draft", schema)),
                forcedTool, 0.1, 2048);
    }

    private static String toolCallResponse() {
        return """
                {"id":"chatcmpl-1","model":"openai/gpt-oss-120b",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_abc","type":"function","function":{
                     "name":"submit_draft","arguments":"{\\"recommendation\\":\\"FIGHT\\"}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":1234,"completion_tokens":321}}""";
    }

    @Test
    void sendsSystemPromptToolsAndForcedToolChoice() {
        wiremock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(toolCallResponse())));

        LlmResult result = client().run(request("submit_draft", List.of()));

        var requests = wiremock.findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(requests).hasSize(1);
        JsonNode body = JSON.readTree(requests.get(0).getBodyAsString());
        assertThat(body.path("model").asString()).isEqualTo("openai/gpt-oss-120b");
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("system");
        assertThat(body.path("tools").get(0).path("function").path("name").asString()).isEqualTo("submit_draft");
        assertThat(body.path("tool_choice").path("function").path("name").asString()).isEqualTo("submit_draft");
        assertThat(requests.get(0).getHeader("Authorization")).isEqualTo("Bearer test-key");

        assertThat(result.mode()).isEqualTo(LlmMode.LIVE);
        assertThat(result.provider()).isEqualTo("OpenAI-compatible API");
        assertThat(result.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(result.inputTokens()).isEqualTo(1234);
        assertThat(result.outputTokens()).isEqualTo(321);
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("submit_draft");
            assertThat(call.input().path("recommendation").asString()).isEqualTo("FIGHT");
        });
    }

    @Test
    void toolResultsBecomeTheirOwnToolMessages() {
        wiremock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(toolCallResponse())));

        JsonNode input = JSON.readTree("{}");
        var assistant = new LlmRequest.Message("assistant", null,
                List.of(new LlmRequest.ToolCall("call_1", "get_case_file", input)), List.of());
        var toolTurn = new LlmRequest.Message("user", null, List.of(),
                List.of(new LlmRequest.ToolResult("call_1", "{\"order\":\"ORD-1042\"}")));

        client().run(request(null, List.of(new LlmRequest.Message("user", "Draft a response.", List.of(), List.of()),
                assistant, toolTurn)));

        JsonNode body = JSON.readTree(wiremock.findAll(postRequestedFor(urlEqualTo(PATH))).get(0).getBodyAsString());
        var roles = new java.util.ArrayList<String>();
        body.path("messages").forEach(m -> roles.add(m.path("role").asString()));
        assertThat(roles).containsExactly("system", "user", "assistant", "tool");
        assertThat(body.path("messages").get(2).path("tool_calls").get(0).path("function").path("name").asString())
                .isEqualTo("get_case_file");
        assertThat(body.path("messages").get(3).path("tool_call_id").asString()).isEqualTo("call_1");
        assertThat(body.path("tool_choice").asString()).isEqualTo("auto");
    }

    @Test
    void retriesOnRateLimitAndThenSucceeds() {
        wiremock.stubFor(post(urlEqualTo(PATH)).inScenario("429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("retry-after", "0.01")
                        .withBody("{\"error\":{\"message\":\"Rate limit reached\"}}"))
                .willSetStateTo("second"));
        wiremock.stubFor(post(urlEqualTo(PATH)).inScenario("429")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(toolCallResponse())));

        LlmResult result = client().run(request("submit_draft", List.of()));

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    void aToolCallTheProviderRejectedIsSampledAgain() {
        wiremock.stubFor(post(urlEqualTo(PATH)).inScenario("tool_use_failed")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(400).withBody("""
                        {"error":{"message":"Tool call validation failed: parameters for tool submit_draft did not \
                        match schema","type":"invalid_request_error","code":"tool_use_failed"}}"""))
                .willSetStateTo("second"));
        wiremock.stubFor(post(urlEqualTo(PATH)).inScenario("tool_use_failed")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(toolCallResponse())));

        LlmResult result = client().run(request("submit_draft", List.of()));

        assertThat(result.toolCalls()).hasSize(1);
        assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    void retriesOnServerErrorsButNotOnClientErrors() {
        wiremock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"model_not_found\"}}")));

        assertThatThrownBy(() -> client().run(request("submit_draft", List.of())))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("model_not_found");
        assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);

        wiremock.resetRequests();
        wiremock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody("overloaded")));
        assertThatThrownBy(() -> client().run(request("submit_draft", List.of())))
                .isInstanceOf(LlmException.class);
        assertThat(wiremock.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(4);
    }

    @Test
    void malformedToolArgumentsAreReportedClearly() {
        wiremock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"m","choices":[{"message":{"role":"assistant","tool_calls":[
                          {"id":"c1","type":"function","function":{"name":"submit_draft","arguments":"not json"}}]}}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1}}""")));

        assertThatThrownBy(() -> client().run(request("submit_draft", List.of())))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void groqBaseUrlsAreLabelledWithTheProviderName() {
        assertThat(OpenAiCompatibleLlmClient.providerNameFor("https://api.groq.com/openai/v1"))
                .isEqualTo("Groq (OpenAI-compatible API)");
        assertThat(OpenAiCompatibleLlmClient.providerNameFor("https://example.test/v1"))
                .isEqualTo("OpenAI-compatible API");
    }

    @Test
    void aMissingBaseUrlOrKeyIsRefusedUpFront() {
        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient("", "k", "m", JSON, new RetryPolicy(1, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DP_LLM_BASE_URL");
        assertThatThrownBy(() -> new OpenAiCompatibleLlmClient("https://x.test/v1", " ", "m", JSON,
                new RetryPolicy(1, 0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DP_LLM_API_KEY");
    }

    @Test
    void backoffGrowsExponentially() {
        RetryPolicy policy = new RetryPolicy(4, 1000, millis -> { });
        assertThat(policy.backoffMs(1)).isBetween(1000L, 2000L);
        assertThat(policy.backoffMs(3)).isBetween(4000L, 5000L);
    }
}
