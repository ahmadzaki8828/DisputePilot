package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.domain.LlmMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordedLlmClientTest {

    private static final String TWO_TURN = """
            {
              "caseKey": "inr-delivered-signature",
              "source": "live",
              "provider": "Groq (OpenAI-compatible API)",
              "model": "openai/gpt-oss-120b",
              "promptVersion": "v1",
              "recordedAt": "2026-11-03T00:00:00Z",
              "turns": [
                {"text": null, "toolCalls": [{"id": "c1", "name": "get_shipments", "input": {}}],
                 "inputTokens": 1000, "outputTokens": 20, "latencyMs": 500},
                {"text": null, "toolCalls": [{"id": "c2", "name": "submit_draft",
                 "input": {"recommendation": "FIGHT"}}], "inputTokens": 1500, "outputTokens": 300,
                 "latencyMs": 900}
              ]
            }
            """;

    private static LlmRequest requestWithAssistantTurns(int assistantTurns) {
        List<LlmRequest.Message> messages = new java.util.ArrayList<>();
        messages.add(new LlmRequest.Message("user", "Draft a response.", List.of(), List.of()));
        for (int i = 0; i < assistantTurns; i++) {
            messages.add(new LlmRequest.Message("assistant", null,
                    List.of(new LlmRequest.ToolCall("c" + i, "get_shipments", null)), List.of()));
            messages.add(new LlmRequest.Message("user", null, List.of(),
                    List.of(new LlmRequest.ToolResult("c" + i, "[]"))));
        }
        return new LlmRequest("inr-delivered-signature", "v1", "system", messages, List.of(), null, 0.1, 2048);
    }

    private static RecordedLlmClient client(@TempDir Path dir, String json, String name) throws Exception {
        Files.writeString(dir.resolve(name), json);
        return RecordedLlmClient.fromDirectory(dir);
    }

    @Test
    void replaysTheTurnMatchingHowFarTheAgentHasGot(@TempDir Path dir) throws Exception {
        RecordedLlmClient c = client(dir, TWO_TURN, "inr.json");

        LlmResult first = c.run(requestWithAssistantTurns(0));
        assertThat(first.toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("get_shipments"));
        assertThat(first.inputTokens()).isEqualTo(1000);

        LlmResult second = c.run(requestWithAssistantTurns(1));
        assertThat(second.toolCalls()).singleElement()
                .satisfies(call -> assertThat(call.name()).isEqualTo("submit_draft"));
        assertThat(second.outputTokens()).isEqualTo(300);
    }

    @Test
    void namesTheProviderAndModelThatProducedTheRecording(@TempDir Path dir) throws Exception {
        LlmResult r = client(dir, TWO_TURN, "inr.json").run(requestWithAssistantTurns(0));
        assertThat(r.mode()).isEqualTo(LlmMode.RECORDED);
        assertThat(r.provider()).isEqualTo("Recorded: Groq (OpenAI-compatible API)");
        assertThat(r.model()).isEqualTo("openai/gpt-oss-120b");
    }

    @Test
    void handWrittenRecordingsAreLabelledSampleNotRecorded(@TempDir Path dir) throws Exception {
        String sample = TWO_TURN.replace("\"source\": \"live\"", "\"source\": \"sample\"");
        assertThat(client(dir, sample, "inr.json").run(requestWithAssistantTurns(0)).mode())
                .isEqualTo(LlmMode.SAMPLE);
    }

    @Test
    void anUnknownCaseSaysWhatIsAvailable(@TempDir Path dir) throws Exception {
        RecordedLlmClient c = client(dir, TWO_TURN, "inr.json");
        LlmRequest unknown = new LlmRequest("no-such-case", "v1", "system", List.of(), List.of(), null, 0.1, 10);
        assertThatThrownBy(() -> c.run(unknown))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("no-such-case")
                .hasMessageContaining("inr-delivered-signature");
    }

    @Test
    void runningPastTheEndOfARecordingIsAClearError(@TempDir Path dir) throws Exception {
        RecordedLlmClient c = client(dir, TWO_TURN, "inr.json");
        assertThatThrownBy(() -> c.run(requestWithAssistantTurns(2)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("only 2 turn(s)");
    }

    @Test
    void aRecordingWithoutACaseKeyIsRejectedOnLoad(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("bad.json"), "{\"turns\":[{\"text\":\"hi\"}]}");
        assertThatThrownBy(() -> RecordedLlmClient.fromDirectory(dir))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("caseKey");
    }

    @Test
    void anEmptyDirectoryLoadsWithNoRecordings(@TempDir Path dir) {
        assertThat(RecordedLlmClient.fromDirectory(dir).caseKeys()).isEmpty();
    }
}
