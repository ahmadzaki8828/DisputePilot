import { describe, expect, it, vi } from "vitest";
import type { AgAiConversationItem, AgLlmRequest } from "ag-studio";
import { chatCompletionsAdapter, cleanSchema, fromChatCompletion, toChatMessages, toChatRequest } from "./chat-adapter";

const user = (text: string): AgAiConversationItem => ({ kind: "input", type: "message", role: "user", content: [{ type: "text", text }] } as AgAiConversationItem);

describe("chat adapter", () => {
  it("groups tool calls onto one assistant message and sends results as tool messages", () => {
    const items: AgAiConversationItem[] = [
      user("Which reason costs most?"),
      { id: "m1", kind: "output", type: "message", role: "assistant", status: "completed", content: [{ type: "text", text: "Checking.", annotations: [] }] },
      { id: "c1", kind: "output", type: "function_call", callId: "call-1", name: "query", arguments: '{"a":1}' },
      { id: "c2", kind: "output", type: "function_call", callId: "call-2", name: "add_widget", arguments: "" },
      { type: "function_call_output", callId: "call-1", output: "rows", status: "completed" },
      { type: "function_call_output", callId: "call-2", output: "ok", status: "completed" },
      { id: "r1", kind: "output", type: "reasoning", summary: [] },
    ];
    const msgs = toChatMessages(items, "You edit dashboards.");
    expect(msgs.map((m) => m.role)).toEqual(["system", "user", "assistant", "tool", "tool"]);
    const assistant = msgs[2] as Extract<(typeof msgs)[number], { role: "assistant" }>;
    expect(assistant.content).toBe("Checking.");
    expect(assistant.tool_calls?.map((c) => c.id)).toEqual(["call-1", "call-2"]);
    expect(assistant.tool_calls?.[1].function.arguments).toBe("{}");
  });

  it("maps tool choice and JSON output, and strips Studio's optional-value sentinels from schemas", () => {
    const req = {
      input: [user("hi")],
      tools: [{ name: "add", description: "Adds", parameters: { type: "object", properties: { x: { anyOf: [{ type: "string" }, { not: {} }] } } } }],
      toolChoice: { name: "add" },
      responseFormat: { type: "json", name: "out", schema: { type: "object" } },
    } as unknown as AgLlmRequest;
    const body = toChatRequest(req);
    expect(body.tool_choice).toEqual({ type: "function", function: { name: "add" } });
    expect(body.tools?.[0].function.parameters).toEqual({ type: "object", properties: { x: { type: "string" } } });
    expect(body.response_format?.json_schema.name).toBe("out");
    expect(cleanSchema({ anyOf: [{ type: "string" }, { type: "null" }, { not: {} }] })).toEqual({ anyOf: [{ type: "string" }, { type: "null" }] });
  });

  it("turns a completion with reasoning, text and a tool call into Studio events and output", () => {
    const { events, response } = fromChatCompletion({
      id: "x",
      model: "m",
      choices: [{ finish_reason: "tool_calls", message: { reasoning: "think", content: "Adding a chart.", tool_calls: [{ id: "t1", type: "function", function: { name: "add_widget", arguments: '{"type":"grid"}' } }] } }],
      usage: { prompt_tokens: 3, completion_tokens: 4, total_tokens: 7 },
    });
    expect(events.map((e) => e.type)).toEqual([
      "REASONING_MESSAGE_START", "REASONING_MESSAGE_CONTENT", "REASONING_MESSAGE_END",
      "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT", "TEXT_MESSAGE_END",
      "TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_END",
    ]);
    expect(response.output.map((o) => o.type)).toEqual(["reasoning", "message", "function_call"]);
    expect(response.status).toBe("completed");
    expect(response.usage?.totalTokens).toBe(7);
  });

  it("posts to the app's own route, never a provider, and surfaces the server's error detail", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(JSON.stringify({ detail: "Try again later." }), { status: 429 }));
    const handler = chatCompletionsAdapter("/analytics/agent", fetchImpl as unknown as typeof fetch).executeTurn({ input: [user("hi")], responseFormat: { type: "text" } });
    const seen = [];
    for await (const e of handler.stream) seen.push(e);
    await expect(handler.complete).rejects.toThrow("Try again later.");
    expect(seen).toEqual([]);
    expect(fetchImpl.mock.calls[0][0]).toBe("/analytics/agent");
    const sent = JSON.parse(fetchImpl.mock.calls[0][1].body);
    expect(sent).not.toHaveProperty("model");
    expect(fetchImpl.mock.calls[0][1].headers).not.toHaveProperty("Authorization");
  });
});
