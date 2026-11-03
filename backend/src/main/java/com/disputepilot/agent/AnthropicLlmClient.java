package com.disputepilot.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoice;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.disputepilot.domain.LlmMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live model access through the Claude API using the official Java SDK (plan §9.1). The API key comes from
 * {@code DP_LLM_API_KEY} and is passed explicitly - never from the ambient environment, so Claude Code's own
 * {@code ANTHROPIC_API_KEY} can never be picked up by accident.
 *
 * <p>The system prompt and the tool definitions are marked with ephemeral cache control, so repeated turns in
 * the agent loop reuse the cached prefix.
 */
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final JsonMapper mapper;
    private final String model;
    private final RetryPolicy retry;

    public AnthropicLlmClient(String apiKey, String baseUrl, String model, JsonMapper mapper, RetryPolicy retry) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("DP_LLM_API_KEY is required for live mode");
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        this.client = builder.build();
        this.model = model;
        this.mapper = mapper;
        this.retry = retry;
    }

    @Override
    public String providerName() {
        return "Anthropic Claude API";
    }

    @Override
    public LlmResult run(LlmRequest request) {
        MessageCreateParams params = buildParams(request);
        long start = System.nanoTime();
        Message message = retry.call(() -> send(params));
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        String text = null;
        List<LlmRequest.ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.text().isPresent() && text == null) {
                text = block.text().get().text();
            }
            if (block.toolUse().isPresent()) {
                var use = block.toolUse().get();
                calls.add(new LlmRequest.ToolCall(use.id(), use.name(), toJsonNode(use._input())));
            }
        }
        return new LlmResult(text, calls, LlmMode.LIVE, providerName(), message.model().toString(),
                (int) message.usage().inputTokens(), (int) message.usage().outputTokens(), latencyMs);
    }

    private Message send(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (com.anthropic.errors.RateLimitException e) {
            throw new RetryPolicy.RateLimitedException(providerName(), e.getMessage(), null);
        } catch (com.anthropic.errors.AnthropicServiceException e) {
            throw new LlmException(providerName(), e.statusCode(), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new LlmException(providerName(), 0, "call failed: " + e.getMessage(), e);
        }
    }

    MessageCreateParams buildParams(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(request.maxTokens());
        // The current Claude models take no temperature parameter; determinism comes from the forced
        // submit_draft tool and its JSON schema instead. request.temperature() applies to the
        // OpenAI-compatible provider.

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            builder.addSystemMessageOfBlocks(List.of(TextBlockParam.builder()
                    .text(request.systemPrompt())
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()));
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            List<ToolUnion> tools = new ArrayList<>();
            for (int i = 0; i < request.tools().size(); i++) {
                LlmRequest.ToolDefinition t = request.tools().get(i);
                Tool.Builder tool = Tool.builder()
                        .name(t.name())
                        .description(t.description())
                        .inputSchema(inputSchema(t.inputSchema()));
                if (i == request.tools().size() - 1) {
                    // Cache control on the last tool covers the whole tool block.
                    tool.cacheControl(CacheControlEphemeral.builder().build());
                }
                tools.add(ToolUnion.ofTool(tool.build()));
            }
            builder.tools(tools);
            builder.toolChoice(request.forcedTool() == null || request.forcedTool().isBlank()
                    ? ToolChoice.ofAuto(ToolChoiceAuto.builder().build())
                    : ToolChoice.ofTool(ToolChoiceTool.builder().name(request.forcedTool()).build()));
        }

        for (LlmRequest.Message m : request.messages()) {
            MessageParam.Role role = "assistant".equals(m.role())
                    ? MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
            List<ContentBlockParam> blocks = new ArrayList<>();
            if (m.toolResults() != null) {
                for (LlmRequest.ToolResult r : m.toolResults()) {
                    blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(r.toolCallId())
                            .content(r.content())
                            .build()));
                }
            }
            if (m.text() != null && !m.text().isBlank()) {
                blocks.add(ContentBlockParam.ofText(m.text()));
            }
            if (m.toolCalls() != null) {
                for (LlmRequest.ToolCall c : m.toolCalls()) {
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(c.id())
                            .name(c.name())
                            .input(JsonValue.from(toMap(c.input())))
                            .build()));
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            builder.addMessage(role == MessageParam.Role.ASSISTANT
                    ? MessageParam.ofAssistantOfContentBlockParams(blocks)
                    : MessageParam.ofUserOfContentBlockParams(blocks));
        }
        return builder.build();
    }

    /** Our JSON-schema node becomes the SDK's input schema, properties and all. */
    private Tool.InputSchema inputSchema(JsonNode schema) {
        Tool.InputSchema.Builder builder = Tool.InputSchema.builder();
        Map<String, Object> asMap = toMap(schema);
        Object properties = asMap.get("properties");
        if (properties instanceof Map<?, ?> props) {
            Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
            props.forEach((k, v) -> p.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
            builder.properties(p.build());
        }
        Object required = asMap.get("required");
        if (required instanceof List<?> list) {
            builder.required(list.stream().map(String::valueOf).toList());
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) mapper.convertValue(node, Map.class);
    }

    private JsonNode toJsonNode(JsonValue value) {
        Object plain = value.convert(Object.class);
        return mapper.valueToTree(plain);
    }
}
