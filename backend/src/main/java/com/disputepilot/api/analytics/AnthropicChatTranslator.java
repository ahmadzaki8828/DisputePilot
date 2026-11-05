package com.disputepilot.api.analytics;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The dashboard agent speaks OpenAI Chat Completions (that is what the browser adapter sends). When the app's
 * provider is the Claude API this turns that request into a Messages API request and the answer back into a chat
 * completion, so the browser never needs to know which provider is behind it.
 */
final class AnthropicChatTranslator {

    private AnthropicChatTranslator() {
    }

    static ObjectNode toMessages(JsonNode chat, String model, int maxTokens, JsonMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("model", model);
        out.put("max_tokens", maxTokens);
        if (chat.has("temperature")) {
            out.set("temperature", chat.get("temperature"));
        }
        StringBuilder system = new StringBuilder();
        ArrayNode messages = out.putArray("messages");
        for (JsonNode m : chat.path("messages")) {
            String role = m.path("role").asString("");
            switch (role) {
                case "system", "developer" -> appendLine(system, text(m.path("content")));
                case "tool" -> {
                    ObjectNode block = mapper.createObjectNode();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", m.path("tool_call_id").asString(""));
                    block.put("content", text(m.path("content")));
                    // Consecutive tool results belong in one user turn.
                    userTurn(messages).add(block);
                }
                case "assistant" -> {
                    ArrayNode content = mapper.createArrayNode();
                    String t = text(m.path("content"));
                    if (!t.isBlank()) {
                        content.addObject().put("type", "text").put("text", t);
                    }
                    for (JsonNode call : m.path("tool_calls")) {
                        ObjectNode use = content.addObject();
                        use.put("type", "tool_use");
                        use.put("id", call.path("id").asString(""));
                        use.put("name", call.path("function").path("name").asString(""));
                        use.set("input", parseArgs(call.path("function").path("arguments").asString("{}"), mapper));
                    }
                    if (!content.isEmpty()) {
                        ObjectNode msg = messages.addObject();
                        msg.put("role", "assistant");
                        msg.set("content", content);
                    }
                }
                default -> {
                    String t = text(m.path("content"));
                    if (!t.isBlank()) {
                        userTurn(messages).addObject().put("type", "text").put("text", t);
                    }
                }
            }
        }
        JsonNode format = chat.path("response_format");
        if ("json_schema".equals(format.path("type").asString(""))) {
            appendLine(system, "Reply with only a JSON object that matches this JSON schema, no prose: "
                    + format.path("json_schema").path("schema"));
        }
        if (!system.isEmpty()) {
            out.put("system", system.toString());
        }
        ArrayNode tools = mapper.createArrayNode();
        for (JsonNode t : chat.path("tools")) {
            JsonNode fn = t.path("function");
            ObjectNode tool = tools.addObject();
            tool.put("name", fn.path("name").asString(""));
            tool.put("description", fn.path("description").asString(""));
            tool.set("input_schema", fn.has("parameters") ? fn.get("parameters")
                    : mapper.createObjectNode().put("type", "object"));
        }
        if (!tools.isEmpty()) {
            out.set("tools", tools);
            JsonNode choice = chat.path("tool_choice");
            ObjectNode tc = mapper.createObjectNode();
            if (choice.isObject()) {
                tc.put("type", "tool").put("name", choice.path("function").path("name").asString(""));
            } else {
                switch (choice.asString("auto")) {
                    case "required" -> tc.put("type", "any");
                    case "none" -> tc.put("type", "none");
                    default -> tc.put("type", "auto");
                }
            }
            out.set("tool_choice", tc);
        }
        return out;
    }

    static ObjectNode toChatCompletion(JsonNode msg, JsonMapper mapper) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", msg.path("id").asString(""));
        out.put("object", "chat.completion");
        out.put("model", msg.path("model").asString(""));
        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode calls = mapper.createArrayNode();
        for (JsonNode block : msg.path("content")) {
            switch (block.path("type").asString("")) {
                case "text" -> text.append(block.path("text").asString(""));
                case "tool_use" -> {
                    ObjectNode call = calls.addObject();
                    call.put("id", block.path("id").asString(""));
                    call.put("type", "function");
                    call.putObject("function").put("name", block.path("name").asString(""))
                            .put("arguments", block.path("input").toString());
                }
                default -> {
                    // thinking and other blocks are not part of the chat completion
                }
            }
        }
        message.put("content", text.toString());
        if (!calls.isEmpty()) {
            message.set("tool_calls", calls);
        }
        String stop = msg.path("stop_reason").asString("end_turn");
        choice.put("finish_reason", switch (stop) {
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> "stop";
        });
        JsonNode usage = msg.path("usage");
        int in = usage.path("input_tokens").asInt(0);
        int outTokens = usage.path("output_tokens").asInt(0);
        out.putObject("usage").put("prompt_tokens", in).put("completion_tokens", outTokens)
                .put("total_tokens", in + outTokens);
        return out;
    }

    private static ArrayNode userTurn(ArrayNode messages) {
        JsonNode last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && "user".equals(last.path("role").asString(""))) {
            return (ArrayNode) last.get("content");
        }
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        return msg.putArray("content");
    }

    /** Chat content is either a string or an array of {type: text, text} parts. */
    private static String text(JsonNode content) {
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                sb.append(part.path("text").asString(""));
            }
            return sb.toString();
        }
        return content.isNull() || content.isMissingNode() ? "" : content.asString("");
    }

    private static JsonNode parseArgs(String json, JsonMapper mapper) {
        try {
            JsonNode node = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return node.isObject() ? node : mapper.createObjectNode();
        } catch (RuntimeException e) {
            return mapper.createObjectNode();
        }
    }

    private static void appendLine(StringBuilder sb, String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append(line);
    }
}
