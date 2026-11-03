package com.disputepilot.agent;

import com.disputepilot.domain.LlmMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Live model access over an OpenAI-compatible Chat Completions API (plan §9.1). Used with Groq's free tier
 * ({@code DP_LLM_BASE_URL=https://api.groq.com/openai/v1}, {@code DP_LLM_MODEL=openai/gpt-oss-120b}), and with
 * any other OpenAI-compatible endpoint.
 *
 * <p>Tool use maps onto {@code tools[]} + {@code tool_calls}; the agent's forced final tool maps onto
 * {@code tool_choice: {"type":"function","function":{"name":"submit_draft"}}}. Prompt caching is automatic
 * prefix caching on these providers - the system prompt and tool definitions are sent first and unchanged on
 * every turn so the shared prefix is reused.
 *
 * <p>Rate limits are the normal case on a free tier, so every call goes through {@link RetryPolicy} and 429
 * responses are retried with the provider's own {@code retry-after} hint.
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final RestClient http;
    private final JsonMapper mapper;
    private final String model;
    private final String provider;
    private final RetryPolicy retry;

    public OpenAiCompatibleLlmClient(String baseUrl, String apiKey, String model, JsonMapper mapper,
                                     RetryPolicy retry) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("DP_LLM_BASE_URL is required for the openai-compatible provider");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DP_LLM_API_KEY is required for live mode");
        }
        this.http = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.model = model;
        this.mapper = mapper;
        this.retry = retry;
        this.provider = providerNameFor(baseUrl);
    }

    /** A human-readable provider name for labelling drafts, recordings and the eval report. */
    public static String providerNameFor(String baseUrl) {
        String host = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (host.contains("groq.com")) {
            return "Groq (OpenAI-compatible API)";
        }
        if (host.contains("openai.com")) {
            return "OpenAI";
        }
        return "OpenAI-compatible API";
    }

    @Override
    public String providerName() {
        return provider;
    }

    @Override
    public LlmResult run(LlmRequest request) {
        ObjectNode body = buildBody(request);
        long start = System.nanoTime();
        JsonNode response = retry.call(() -> post(body));
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        return parse(response, latencyMs);
    }

    private JsonNode post(ObjectNode body) {
        try {
            return http.post().uri("/chat/completions").body(body.toString()).exchange((req, res) -> {
                int status = res.getStatusCode().value();
                String text = new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (status == 429) {
                    throw new RetryPolicy.RateLimitedException(provider,
                            "rate limited: " + summarise(text), retryAfter(res.getHeaders()));
                }
                if (status < 200 || status >= 300) {
                    throw new LlmException(provider, status, "HTTP " + status + ": " + summarise(text));
                }
                return mapper.readTree(text);
            });
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException(provider, 0, "call failed: " + e.getMessage(), e);
        }
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst("retry-after");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Seconds, possibly fractional (Groq sends e.g. "7.5").
            return Duration.ofMillis(Math.round(Double.parseDouble(value.trim()) * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String summarise(String body) {
        if (body == null) {
            return "(no body)";
        }
        String trimmed = body.strip();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "..." : trimmed;
    }

    ObjectNode buildBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", request.temperature());
        body.put("max_completion_tokens", request.maxTokens());

        ArrayNode messages = body.putArray("messages");
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", request.systemPrompt());
        }
        for (LlmRequest.Message m : request.messages()) {
            addMessage(messages, m);
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (LlmRequest.ToolDefinition t : request.tools()) {
                ObjectNode tool = tools.addObject();
                tool.put("type", "function");
                ObjectNode fn = tool.putObject("function");
                fn.put("name", t.name());
                fn.put("description", t.description());
                fn.set("parameters", t.inputSchema());
            }
            if (request.forcedTool() != null && !request.forcedTool().isBlank()) {
                ObjectNode choice = body.putObject("tool_choice");
                choice.put("type", "function");
                choice.putObject("function").put("name", request.forcedTool());
            } else {
                body.put("tool_choice", "auto");
            }
        }
        return body;
    }

    private void addMessage(ArrayNode messages, LlmRequest.Message m) {
        boolean hasToolResults = m.toolResults() != null && !m.toolResults().isEmpty();
        if (hasToolResults) {
            // OpenAI-compatible APIs carry each tool result as its own "tool" message.
            for (LlmRequest.ToolResult r : m.toolResults()) {
                ObjectNode node = messages.addObject();
                node.put("role", "tool");
                node.put("tool_call_id", r.toolCallId());
                node.put("content", r.content());
            }
        }
        boolean hasText = m.text() != null && !m.text().isBlank();
        boolean hasToolCalls = m.toolCalls() != null && !m.toolCalls().isEmpty();
        if (!hasText && !hasToolCalls) {
            return;
        }
        ObjectNode node = messages.addObject();
        node.put("role", m.role());
        node.put("content", hasText ? m.text() : "");
        if (hasToolCalls) {
            ArrayNode calls = node.putArray("tool_calls");
            for (LlmRequest.ToolCall c : m.toolCalls()) {
                ObjectNode call = calls.addObject();
                call.put("id", c.id());
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", c.name());
                fn.put("arguments", c.input() == null ? "{}" : c.input().toString());
            }
        }
    }

    private LlmResult parse(JsonNode response, long latencyMs) {
        JsonNode message = response.path("choices").path(0).path("message");
        String text = message.path("content").asString(null);
        List<LlmRequest.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode fn = call.path("function");
            String arguments = fn.path("arguments").asString("{}");
            JsonNode input;
            try {
                input = arguments.isBlank() ? mapper.createObjectNode() : mapper.readTree(arguments);
            } catch (RuntimeException e) {
                throw new LlmException(provider, 0,
                        "tool call arguments were not valid JSON: " + summarise(arguments), e);
            }
            calls.add(new LlmRequest.ToolCall(call.path("id").asString("call-" + calls.size()),
                    fn.path("name").asString(), input));
        }
        JsonNode usage = response.path("usage");
        String servedModel = response.path("model").asString(model);
        log.debug("{} answered with {} tool call(s) in {} ms ({} in / {} out tokens)",
                servedModel, calls.size(), latencyMs,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
        return new LlmResult(text, calls, LlmMode.LIVE, provider, servedModel,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0), latencyMs);
    }
}
