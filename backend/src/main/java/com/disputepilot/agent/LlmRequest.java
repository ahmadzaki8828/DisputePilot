package com.disputepilot.agent;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** One model turn: system prompt, conversation so far, tool definitions, and optionally a forced tool. */
public record LlmRequest(
        String caseKey,
        String promptVersion,
        String systemPrompt,
        List<Message> messages,
        List<ToolDefinition> tools,
        String forcedTool,
        double temperature,
        int maxTokens) {

    /** role is "user" or "assistant"; toolCalls/toolResults carry tool-use turns. */
    public record Message(String role, String text, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
    }

    public record ToolDefinition(String name, String description, JsonNode inputSchema) {
    }

    public record ToolCall(String id, String name, JsonNode input) {
    }

    public record ToolResult(String toolCallId, String content) {
    }
}
