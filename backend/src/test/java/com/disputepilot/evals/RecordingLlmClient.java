package com.disputepilot.evals;

import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.LlmRequest;
import com.disputepilot.agent.LlmResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wraps a live client and keeps every turn, so a live eval run produces the recordings that let the whole app
 * (and CI) run afterwards with no key. Each recording is labelled with the provider and model that produced it.
 */
public class RecordingLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final JsonMapper mapper;
    private final Map<String, List<LlmResult>> turns = new LinkedHashMap<>();
    private final Map<String, String> promptVersions = new LinkedHashMap<>();

    public RecordingLlmClient(LlmClient delegate, JsonMapper mapper) {
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public LlmResult run(LlmRequest request) {
        LlmResult result = delegate.run(request);
        turns.computeIfAbsent(request.caseKey(), k -> new ArrayList<>()).add(result);
        promptVersions.put(request.caseKey(), request.promptVersion());
        return result;
    }

    /** Drops any turns kept for a case, so a re-run of that case starts clean. */
    public void forget(String caseKey) {
        turns.remove(caseKey);
    }

    public boolean has(String caseKey) {
        return turns.containsKey(caseKey);
    }

    /** The recording for one case, in the shape RecordedLlmClient replays. */
    public ObjectNode recordingFor(String caseKey, String recordedAt) {
        List<LlmResult> results = turns.get(caseKey);
        if (results == null || results.isEmpty()) {
            throw new IllegalStateException("No turns recorded for case " + caseKey);
        }
        LlmResult last = results.get(results.size() - 1);
        ObjectNode root = mapper.createObjectNode();
        root.put("_note", "Recorded from a real provider call during a live eval run. Replayed when "
                + "DP_LLM_MODE=recorded so the app runs with no API key.");
        root.put("caseKey", caseKey);
        root.put("source", "live");
        root.put("provider", last.provider());
        root.put("model", last.model());
        root.put("promptVersion", promptVersions.get(caseKey));
        root.put("recordedAt", recordedAt);
        ArrayNode turnsNode = root.putArray("turns");
        for (LlmResult r : results) {
            ObjectNode turn = turnsNode.addObject();
            if (r.text() == null) {
                turn.putNull("text");
            } else {
                turn.put("text", r.text());
            }
            ArrayNode calls = turn.putArray("toolCalls");
            for (LlmRequest.ToolCall call : r.toolCalls()) {
                ObjectNode c = calls.addObject();
                c.put("id", call.id());
                c.put("name", call.name());
                c.set("input", call.input());
            }
            turn.put("inputTokens", r.inputTokens());
            turn.put("outputTokens", r.outputTokens());
            turn.put("latencyMs", r.latencyMs());
        }
        return root;
    }
}
