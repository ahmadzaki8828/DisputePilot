package com.disputepilot.agent;

import com.disputepilot.domain.LlmMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** A test double that plays a scripted sequence of model turns and records every request it was given. */
class ScriptedLlmClient implements LlmClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Deque<LlmResult> script = new ArrayDeque<>();
    private final List<LlmRequest> requests = new ArrayList<>();

    static LlmResult toolCall(String name, String inputJson) {
        return new LlmResult(null,
                List.of(new LlmRequest.ToolCall("call-" + name, name, JSON.readTree(inputJson))),
                LlmMode.LIVE, "Scripted provider", "scripted-model", 100, 40, 12);
    }

    static LlmResult text(String text) {
        return new LlmResult(text, List.of(), LlmMode.LIVE, "Scripted provider", "scripted-model", 10, 5, 3);
    }

    ScriptedLlmClient then(LlmResult result) {
        script.add(result);
        return this;
    }

    List<LlmRequest> requests() {
        return requests;
    }

    @Override
    public String providerName() {
        return "Scripted provider";
    }

    @Override
    public LlmResult run(LlmRequest request) {
        requests.add(request);
        if (script.isEmpty()) {
            throw new IllegalStateException("The agent asked for turn " + requests.size()
                    + " but the script has run out");
        }
        return script.poll();
    }
}
