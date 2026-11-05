package com.disputepilot.api.analytics;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.disputepilot.PostgresTestSupport;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard agent against a stubbed OpenAI-compatible provider: model and limits are set server-side. */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock", "dp.llm.mode=live",
        "dp.llm.api-key=test-key", "dp.llm.model=test-model", "dp.llm.provider=openai-compatible",
        "dp.llm.max-attempts=1", "dp.analytics.agent-turns-per-hour=2"})
@AutoConfigureMockMvc
class AnalyticsAgentProxyTest {

    private static final String TOKEN = "test-token";
    private static final WireMockServer PROVIDER = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PROVIDER.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
        registry.add("dp.llm.base-url", () -> PROVIDER.baseUrl() + "/openai/v1");
    }

    @AfterAll
    static void stop() {
        PROVIDER.stop();
    }

    @Autowired MockMvc mvc;

    @BeforeEach
    void stub() {
        PROVIDER.resetAll();
        PROVIDER.stubFor(WireMock.post("/openai/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"c1","model":"test-model","choices":[{"index":0,"finish_reason":"tool_calls",
                         "message":{"role":"assistant","content":"","tool_calls":[{"id":"t1","type":"function",
                         "function":{"name":"add_widget","arguments":"{\\"type\\":\\"grid\\"}"}}]}}]}""")));
    }

    private static final String TURN = """
            {"model":"some-expensive-model","max_completion_tokens":999999,"stream":true,"user":"x",
             "messages":[{"role":"user","content":"Which dispute reason costs me most?"}],
             "tools":[{"type":"function","function":{"name":"add_widget","parameters":{"type":"object"}}}],
             "tool_choice":"auto"}""";

    @Test
    void forwardsATurnWithTheServerModelKeyAndCapsThenEnforcesTheHourlyAllowance() throws Exception {
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("add_widget"));

        PROVIDER.verify(postRequestedFor(urlEqualTo("/openai/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("test-model")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(matchingJsonPath("$.max_completion_tokens",
                        equalTo(String.valueOf(AnalyticsAgentProxy.MAX_OUTPUT_TOKENS))))
                .withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("add_widget"))));
        String sent = PROVIDER.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(sent).doesNotContain("\"user\":\"x\"").doesNotContain("some-expensive-model");

        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isOk());
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isTooManyRequests());
        // Another visitor has their own allowance.
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "198.51.100.9")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isOk());
    }

    @Test
    void aProviderErrorBecomesA502WithoutLeakingTheProviderBody() throws Exception {
        PROVIDER.stubFor(WireMock.post("/openai/v1/chat/completions")
                .willReturn(aResponse().withStatus(500).withBody("internal secret detail")));
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "192.0.2.50")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void aToolCallTheProviderRejectedComesBackAsAToolCallSoStudioCanReportTheSchemaError() throws Exception {
        PROVIDER.stubFor(WireMock.post("/openai/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"message":"Tool call validation failed: tool call validation failed: parameters for \
                        tool execute_query did not match schema: errors: [missing properties: 'query']",
                        "type":"invalid_request_error","code":"tool_use_failed",
                        "failed_generation":"{\\"name\\":\\"execute_query\\",\\"arguments\\":{\\"tableId\\":\\"disputes\\"}}"}}""")));
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "192.0.2.52")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("execute_query"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.arguments")
                        .value("{\"tableId\":\"disputes\"}"));
    }

    @Test
    void aRejectedTurnWithNoToolAttemptComesBackAsTheModelsText() throws Exception {
        PROVIDER.stubFor(WireMock.post("/openai/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error":{"message":"Tool choice is required, but model did not call a tool",
                        "code":"tool_use_failed","failed_generation":"I need a table name."}}""")));
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "192.0.2.53")
                        .contentType(MediaType.APPLICATION_JSON).content(TURN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("I need a table name."));
    }

    @Test
    void rejectsARequestWithoutMessages() throws Exception {
        mvc.perform(post("/api/analytics/agent").header("X-Admin-Token", TOKEN)
                        .header("X-Forwarded-For", "192.0.2.51")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[]}"))
                .andExpect(status().isBadRequest());
    }
}
