package com.disputepilot.api.analytics;

import com.disputepilot.agent.LlmException;
import com.disputepilot.agent.OpenAiCompatibleLlmClient;
import com.disputepilot.agent.RetryPolicy;
import com.disputepilot.common.AppProperties;
import com.disputepilot.common.limits.LlmDailyCap;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Server-side model access for the AG Studio dashboard agent. The browser's adapter sends an OpenAI Chat
 * Completions request (via the web server, which adds the admin token); this forwards it to the app's own
 * configured provider ({@code DP_LLM_PROVIDER}, {@code DP_LLM_BASE_URL}, {@code DP_LLM_API_KEY},
 * {@code DP_LLM_MODEL}), so the key never leaves the backend.
 *
 * <p>Guarded like drafts (plan §9.2 step 7): a per-client-IP hourly allowance and the shared daily cap on live
 * calls. One dashboard question takes several model turns (the agent calls Studio tools and reads their
 * results), so the hourly allowance counts turns and defaults to ten times the live-draft limit.
 * The model is fixed server-side and only the request fields an agent turn needs are passed on.
 */
@Service
public class AnalyticsAgentProxy {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsAgentProxy.class);

    /** Fields of a Chat Completions request the dashboard agent may set; everything else is dropped. */
    private static final Set<String> PASSED_FIELDS = Set.of("messages", "tools", "tool_choice", "response_format",
            "temperature", "parallel_tool_calls");
    static final int MAX_OUTPUT_TOKENS = 8192;
    static final int MAX_REQUEST_BYTES = 2_000_000;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    /** What the dashboard shows about the agent: whether it can run, and which provider and model answer. */
    public record AgentInfo(boolean enabled, String provider, String model, int turnsPerHour, String reason) {
    }

    public static class AgentException extends RuntimeException {
        private final int status;

        AgentException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final AppProperties.Llm llm;
    private final LlmDailyCap dailyCap;
    private final JsonMapper mapper;
    private final RetryPolicy retry;
    private final int turnsPerHour;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final RestClient http;

    public AnalyticsAgentProxy(AppProperties props, LlmDailyCap dailyCap, JsonMapper mapper,
                               @Value("${dp.analytics.agent-turns-per-hour:0}") int turnsPerHour) {
        this.llm = props.llm();
        this.dailyCap = dailyCap;
        this.mapper = mapper;
        this.retry = new RetryPolicy(llm.maxAttempts(), llm.retryBaseDelayMs());
        this.turnsPerHour = turnsPerHour > 0 ? turnsPerHour : Math.max(1, props.demo().rateLimit()) * 10;
        this.http = RestClient.builder().build();
    }

    public AgentInfo info() {
        boolean anthropic = "anthropic".equals(llm.effectiveProvider());
        String provider = anthropic ? "Claude API" : OpenAiCompatibleLlmClient.providerNameFor(llm.baseUrl());
        String reason = null;
        if (!"live".equals(llm.effectiveMode())) {
            reason = "The dashboard agent needs a live model, and this deployment replays recorded AI responses "
                    + "(set DP_LLM_API_KEY to enable it).";
        } else if (!llm.hasApiKey()) {
            reason = "DP_LLM_API_KEY is not set, so the dashboard agent is off.";
        } else if (!anthropic && (llm.baseUrl() == null || llm.baseUrl().isBlank())) {
            reason = "DP_LLM_BASE_URL is not set, so the dashboard agent is off.";
        }
        return new AgentInfo(reason == null, provider, llm.effectiveModel(), turnsPerHour, reason);
    }

    /** One agent turn: rate limits, then the provider. Returns a Chat Completions response. */
    public JsonNode turn(String rawBody, String clientIp) {
        AgentInfo info = info();
        if (!info.enabled()) {
            throw new AgentException(503, info.reason());
        }
        if (rawBody == null || rawBody.isBlank()) {
            throw new AgentException(400, "Request body is empty.");
        }
        if (rawBody.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) {
            throw new AgentException(413, "The conversation is too long; start a new one.");
        }
        JsonNode request;
        try {
            request = mapper.readTree(rawBody);
        } catch (RuntimeException e) {
            throw new AgentException(400, "Request body is not valid JSON.");
        }
        if (!request.path("messages").isArray() || request.path("messages").isEmpty()) {
            throw new AgentException(400, "messages must be a non-empty array.");
        }
        if (!take(clientIp)) {
            throw new AgentException(429, "The public demo allows " + turnsPerHour
                    + " dashboard-agent turns per visitor per hour. Try again later.");
        }
        if (!dailyCap.tryAcquire()) {
            throw new AgentException(429, "The public demo's daily limit of " + dailyCap.cap()
                    + " live AI calls has been reached.");
        }
        ObjectNode chat = mapper.createObjectNode();
        for (String field : PASSED_FIELDS) {
            if (request.has(field) && !request.get(field).isNull()) {
                chat.set(field, request.get(field));
            }
        }
        int maxTokens = Math.min(MAX_OUTPUT_TOKENS, Math.max(256,
                request.path("max_completion_tokens").asInt(request.path("max_tokens").asInt(MAX_OUTPUT_TOKENS))));
        try {
            return "anthropic".equals(llm.effectiveProvider()) ? anthropic(chat, maxTokens) : openAi(chat, maxTokens);
        } catch (LlmException e) {
            log.warn("Dashboard agent call to {} failed (status {}): {}", e.provider(), e.httpStatus(),
                    e.getMessage());
            if (e.httpStatus() == 429) {
                throw new AgentException(429, "The model provider is busy (rate limited). Try again in a minute.");
            }
            throw new AgentException(502, "The model provider returned an error"
                    + (e.httpStatus() > 0 ? " (HTTP " + e.httpStatus() + ")" : "") + ". Try again.");
        }
    }

    private JsonNode openAi(ObjectNode chat, int maxTokens) {
        chat.put("model", llm.effectiveModel());
        chat.put("max_completion_tokens", maxTokens);
        chat.put("stream", false);
        String base = trimSlash(llm.baseUrl());
        String provider = OpenAiCompatibleLlmClient.providerNameFor(base);
        try {
            return retry.call(() -> post(provider, base + "/chat/completions",
                    h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + llm.apiKey()), chat));
        } catch (ToolUseFailed e) {
            return recoverToolUse(e.error);
        }
    }

    private static final Pattern FAILED_TOOL = Pattern.compile("parameters for tool (\\S+) did not match schema");

    /**
     * Groq checks tool-call arguments against the schema itself and rejects the whole turn (400 tool_use_failed)
     * when the model gets them wrong. Rather than end the agent's run, hand the attempt back as an ordinary tool
     * call: Studio validates it, returns the schema error as the tool result, and the model corrects itself on the
     * next turn. When no tool was attempted, the model's text is returned as its answer.
     */
    JsonNode recoverToolUse(JsonNode error) {
        String generation = error.path("failed_generation").asString("");
        Matcher m = FAILED_TOOL.matcher(error.path("message").asString(""));
        ObjectNode out = mapper.createObjectNode();
        out.put("id", "recovered-" + System.nanoTime());
        out.put("model", llm.effectiveModel());
        ObjectNode choice = out.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (m.find()) {
            JsonNode args = mapper.createObjectNode();
            try {
                JsonNode parsed = mapper.readTree(generation);
                JsonNode candidate = parsed.has("arguments") ? parsed.get("arguments")
                        : parsed.has("parameters") ? parsed.get("parameters") : parsed;
                if (candidate.isString()) {
                    candidate = mapper.readTree(candidate.asString());
                }
                if (candidate.isObject()) {
                    args = candidate;
                }
            } catch (RuntimeException ignored) {
                // Not JSON: the tool still runs with no arguments and reports what it needs.
            }
            message.put("content", "");
            ObjectNode call = message.putArray("tool_calls").addObject();
            call.put("id", "call_recovered_" + System.nanoTime());
            call.put("type", "function");
            call.putObject("function").put("name", m.group(1).replaceAll("[^A-Za-z0-9_.-]", ""))
                    .put("arguments", args.toString());
            choice.put("finish_reason", "tool_calls");
        } else {
            message.put("content", generation);
            choice.put("finish_reason", "stop");
        }
        return out;
    }

    /** A 400 tool_use_failed from an OpenAI-compatible provider, with the provider's error object. */
    static final class ToolUseFailed extends RuntimeException {
        final transient JsonNode error;

        ToolUseFailed(JsonNode error) {
            super("tool_use_failed");
            this.error = error;
        }
    }

    private JsonNode anthropic(ObjectNode chat, int maxTokens) {
        ObjectNode body = AnthropicChatTranslator.toMessages(chat, llm.effectiveModel(), maxTokens, mapper);
        String base = llm.baseUrl() == null || llm.baseUrl().isBlank() ? "https://api.anthropic.com"
                : trimSlash(llm.baseUrl());
        JsonNode msg = retry.call(() -> post("Claude API", base + "/v1/messages", h -> {
            h.set("x-api-key", llm.apiKey());
            h.set("anthropic-version", "2023-06-01");
        }, body));
        return AnthropicChatTranslator.toChatCompletion(msg, mapper);
    }

    private JsonNode post(String provider, String url, java.util.function.Consumer<HttpHeaders> auth,
                          JsonNode body) {
        try {
            return http.post().uri(url).contentType(MediaType.APPLICATION_JSON).headers(auth)
                    .body(body.toString()).exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (status == 429) {
                            throw new RetryPolicy.RateLimitedException(provider, "rate limited",
                                    retryAfter(res.getHeaders()));
                        }
                        if (status == 400 && text.contains("tool_use_failed")) {
                            JsonNode error = mapper.readTree(text).path("error");
                            if ("tool_use_failed".equals(error.path("code").asString(""))) {
                                throw new ToolUseFailed(error);
                            }
                        }
                        if (status < 200 || status >= 300) {
                            throw new LlmException(provider, status, "HTTP " + status + ": " + summarise(text));
                        }
                        return mapper.readTree(text);
                    });
        } catch (LlmException | ToolUseFailed e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException(provider, 0, "call failed: " + e.getMessage(), e);
        }
    }

    private boolean take(String client) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }
        return buckets.computeIfAbsent(client == null ? "unknown" : client, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(turnsPerHour)
                        .refillIntervally(turnsPerHour, Duration.ofHours(1)).build())
                .build()).tryConsume(1);
    }

    private static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst("retry-after");
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofMillis(Math.round(Double.parseDouble(value.trim()) * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String trimSlash(String url) {
        String u = url == null ? "" : url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String summarise(String body) {
        String t = body == null ? "" : body.strip();
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }
}
