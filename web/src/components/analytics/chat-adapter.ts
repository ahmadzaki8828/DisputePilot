// AG Studio LLM adapter over OpenAI Chat Completions, for the dashboard agent.
//
// AG Studio's docs ship a Responses-API adapter; DisputePilot's model provider (Groq by default, or the Claude API)
// is reached through Chat Completions instead, so this maps Studio's conversation items to chat messages and the
// reply back to Studio's events. It runs in the browser and posts to this app's own /analytics/agent route, which
// forwards to the backend. The backend holds the provider key, fixes the model and applies the demo rate limits;
// nothing secret is ever sent from or to the browser.
//
// One turn is a single non-streamed request: the reply is replayed as start/content/end events once it arrives.
import type {
  AgAiConversationItem,
  AgAiEvent,
  AgAiOutputItem,
  AgAiToolSchema,
  AgLlmAdapter,
  AgLlmRequest,
  AgLlmResponse,
} from "ag-studio";

type Json = Record<string, unknown>;

export type ChatMessage =
  | { role: "system" | "user"; content: string }
  | { role: "assistant"; content: string; tool_calls?: ChatToolCall[] }
  | { role: "tool"; tool_call_id: string; content: string };

export type ChatToolCall = { id: string; type: "function"; function: { name: string; arguments: string } };

export type ChatRequest = {
  messages: ChatMessage[];
  tools?: Array<{ type: "function"; function: { name: string; description: string; parameters: unknown } }>;
  tool_choice?: "auto" | "none" | "required" | { type: "function"; function: { name: string } };
  response_format?: { type: "json_schema"; json_schema: { name: string; description?: string; schema: unknown } };
  parallel_tool_calls?: boolean;
};

export type ChatCompletion = {
  id?: string;
  model?: string;
  created?: number;
  choices?: Array<{
    finish_reason?: string;
    message?: { content?: string | null; reasoning?: string | null; tool_calls?: ChatToolCall[] };
  }>;
  usage?: { prompt_tokens?: number; completion_tokens?: number; total_tokens?: number };
};

const isObj = (v: unknown): v is Json => typeof v === "object" && v !== null && !Array.isArray(v);

/**
 * Studio's schema library marks optional values as `anyOf: [T, { not: {} }]`. Chat providers reject or
 * misread `not`, so those sentinels are dropped; everything else is passed through as written.
 */
export function cleanSchema(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema.map(cleanSchema);
  if (!isObj(schema)) return schema;
  const out: Json = {};
  for (const [k, v] of Object.entries(schema)) out[k] = cleanSchema(v);
  if (Array.isArray(out.anyOf)) {
    const kept = (out.anyOf as unknown[]).filter((b) => !(isObj(b) && Object.keys(b).length === 1 && isObj(b.not) && Object.keys(b.not).length === 0));
    if (kept.length === 1 && isObj(kept[0])) {
      const { anyOf: _drop, ...rest } = out;
      void _drop;
      return { ...(kept[0] as Json), ...rest };
    }
    out.anyOf = kept;
  }
  return out;
}

/** Studio's conversation as Chat Completions messages. Tool calls are grouped onto their assistant message. */
export function toChatMessages(items: AgAiConversationItem[], instructions?: string): ChatMessage[] {
  const messages: ChatMessage[] = [];
  if (instructions?.trim()) messages.push({ role: "system", content: instructions });
  let assistant: Extract<ChatMessage, { role: "assistant" }> | null = null;
  const flush = () => {
    if (assistant) messages.push(assistant);
    assistant = null;
  };
  for (const item of items) {
    if (item.type === "message" && item.kind === "input") {
      flush();
      const text = item.content.map((c) => (c.type === "text" ? c.text : `[${c.type} omitted]`)).join("\n");
      messages.push({ role: item.role === "system" ? "system" : "user", content: text });
    } else if (item.type === "message" && item.kind === "output") {
      const text = item.content.map((c) => (c.type === "text" ? c.text : c.refusal)).join("");
      if (assistant && (assistant.tool_calls?.length ?? 0) === 0) assistant.content += text;
      else {
        flush();
        assistant = { role: "assistant", content: text };
      }
    } else if (item.type === "function_call") {
      assistant ??= { role: "assistant", content: "" };
      (assistant.tool_calls ??= []).push({ id: item.callId, type: "function", function: { name: item.name, arguments: item.arguments || "{}" } });
    } else if (item.type === "function_call_output") {
      flush();
      messages.push({ role: "tool", tool_call_id: item.callId, content: item.output });
    }
    // Reasoning items are the model's own scratch work; they are not sent back.
  }
  flush();
  return messages;
}

export function toChatRequest(request: AgLlmRequest): ChatRequest {
  const body: ChatRequest = { messages: toChatMessages(request.input, request.instructions) };
  const tools = request.tools ?? [];
  if (tools.length > 0) {
    body.tools = tools.map((t: AgAiToolSchema) => ({
      type: "function",
      function: { name: t.name, description: t.description, parameters: cleanSchema(t.parameters) },
    }));
    const choice = request.toolChoice;
    body.tool_choice = !choice ? "auto" : typeof choice === "string" ? choice : { type: "function", function: { name: choice.name } };
    body.parallel_tool_calls = true;
  }
  if (request.responseFormat?.type === "json") {
    const f = request.responseFormat;
    body.response_format = { type: "json_schema", json_schema: { name: f.name, description: f.description, schema: cleanSchema(f.schema) } };
  }
  return body;
}

/** The reply as Studio's events and final response. */
export function fromChatCompletion(reply: ChatCompletion): { events: AgAiEvent[]; response: AgLlmResponse } {
  const id = reply.id || `chat-${Date.now()}`;
  const choice = reply.choices?.[0];
  const message = choice?.message ?? {};
  const events: AgAiEvent[] = [];
  const output: AgAiOutputItem[] = [];

  if (message.reasoning) {
    const rid = `${id}-reasoning`;
    events.push({ type: "REASONING_MESSAGE_START", messageId: rid, role: "reasoning" });
    events.push({ type: "REASONING_MESSAGE_CONTENT", messageId: rid, delta: message.reasoning });
    events.push({ type: "REASONING_MESSAGE_END", messageId: rid });
    output.push({ id: rid, kind: "output", type: "reasoning", summary: [], content: [{ type: "text", text: message.reasoning }] });
  }
  const text = message.content ?? "";
  if (text) {
    const mid = `${id}-message`;
    events.push({ type: "TEXT_MESSAGE_START", messageId: mid, role: "assistant" });
    events.push({ type: "TEXT_MESSAGE_CONTENT", messageId: mid, delta: text });
    events.push({ type: "TEXT_MESSAGE_END", messageId: mid });
    output.push({ id: mid, kind: "output", type: "message", role: "assistant", status: "completed", content: [{ type: "text", text, annotations: [] }] });
  }
  (message.tool_calls ?? []).forEach((call, i) => {
    const callId = call.id || `${id}-call-${i}`;
    const args = call.function?.arguments || "{}";
    events.push({ type: "TOOL_CALL_START", toolCallId: callId, toolCallName: call.function.name });
    events.push({ type: "TOOL_CALL_ARGS", toolCallId: callId, delta: args });
    events.push({ type: "TOOL_CALL_END", toolCallId: callId });
    output.push({ id: callId, kind: "output", type: "function_call", callId, name: call.function.name, arguments: args, status: "completed" });
  });

  const usage = reply.usage;
  const response: AgLlmResponse = {
    id,
    createdAt: reply.created ? reply.created * 1000 : Date.now(),
    output,
    status: choice?.finish_reason === "length" ? "incomplete" : "completed",
    ...(choice?.finish_reason === "length" && { incompleteDetails: { reason: "max_output_tokens" as const } }),
    ...(reply.model && { model: reply.model }),
    ...(usage && {
      usage: { inputTokens: usage.prompt_tokens ?? 0, outputTokens: usage.completion_tokens ?? 0, totalTokens: usage.total_tokens },
    }),
  } as AgLlmResponse;
  return { events, response };
}

/** The adapter Studio's harness calls once per turn. `endpoint` is this app's own route, never the provider. */
export function chatCompletionsAdapter(endpoint = "/analytics/agent", fetchImpl: typeof fetch = (...a) => fetch(...a)): AgLlmAdapter {
  return {
    executeTurn(request, options) {
      let resolve!: (r: AgLlmResponse) => void;
      let reject!: (e: Error) => void;
      const complete = new Promise<AgLlmResponse>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      // A turn is reported failed once, through `complete`; mark it observed for consumers that stop early.
      void complete.catch(() => {});

      async function* run(): AsyncIterableIterator<AgAiEvent> {
        try {
          const res = await fetchImpl(endpoint, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(toChatRequest(request)),
            signal: options?.signal,
          });
          const body = (await res.json().catch(() => ({}))) as ChatCompletion & { detail?: string; title?: string };
          if (!res.ok) throw new Error(body.detail || body.title || `The dashboard agent failed (HTTP ${res.status}).`);
          const { events, response } = fromChatCompletion(body);
          yield* events;
          resolve(response);
        } catch (e) {
          reject(e instanceof Error ? e : new Error(String(e)));
        }
      }
      const stream = run();
      return { stream: { [Symbol.asyncIterator]: () => stream }, complete };
    },
  };
}
