package com.disputepilot.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays recorded provider responses from {@code evals/recordings/} (plan §9.1), so the zero-key demo and CI
 * behave exactly like a live run without calling anyone. Recordings are packaged on the classpath under
 * {@code recordings/} and can also be read from a directory for local iteration.
 *
 * <p>Stateless by design: the turn to replay is derived from how many assistant turns the request already
 * carries, so concurrent drafts for different disputes never interfere.
 */
public class RecordedLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(RecordedLlmClient.class);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Token counts and latency are optional in a recording; absent means zero.
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final Map<String, Recording> byCaseKey;

    /** Loads every recording on the classpath under {@code recordings/}. */
    public RecordedLlmClient() {
        this(loadFromClasspath());
    }

    public RecordedLlmClient(Map<String, Recording> byCaseKey) {
        this.byCaseKey = Map.copyOf(byCaseKey);
        log.info("Recorded LLM mode: {} recording(s) available", this.byCaseKey.size());
    }

    /** Loads recordings from a directory, e.g. the repo's {@code evals/recordings} during development. */
    public static RecordedLlmClient fromDirectory(Path dir) {
        Map<String, Recording> map = new LinkedHashMap<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().forEach(f -> {
                    try (InputStream in = Files.newInputStream(f)) {
                        put(map, MAPPER.readValue(in, Recording.class), f.toString());
                    } catch (IOException e) {
                        throw new UncheckedIOException("Cannot read recording " + f, e);
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot list " + dir, e);
            }
        }
        return new RecordedLlmClient(map);
    }

    private static Map<String, Recording> loadFromClasspath() {
        Map<String, Recording> map = new LinkedHashMap<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:recordings/*.json");
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    put(map, MAPPER.readValue(in, Recording.class), String.valueOf(file.getFilename()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load recordings from the classpath", e);
        }
        return map;
    }

    private static void put(Map<String, Recording> map, Recording recording, String where) {
        if (recording.caseKey() == null || recording.caseKey().isBlank()) {
            throw new IllegalStateException("Recording " + where + " has no caseKey");
        }
        if (recording.turns() == null || recording.turns().isEmpty()) {
            throw new IllegalStateException("Recording " + where + " has no turns");
        }
        map.put(recording.caseKey(), recording);
    }

    private static tools.jackson.databind.JsonNode rewrite(tools.jackson.databind.JsonNode input, String from,
                                                           String to) {
        if (input == null) {
            return null;
        }
        return MAPPER.readTree(MAPPER.writeValueAsString(input).replace(from, to));
    }

    public Set<String> caseKeys() {
        return byCaseKey.keySet();
    }

    public boolean has(String caseKey) {
        return byCaseKey.containsKey(templateOf(caseKey));
    }

    public java.util.Collection<Recording> recordings() {
        return byCaseKey.values();
    }

    /**
     * A case key may name a clone of a recorded dispute as {@code template@cloneId} (a simulated dispute created
     * from a template gets a new ID). The recording of {@code template} is replayed with its dispute ID
     * rewritten to {@code cloneId}, so citations such as {@code dispute:DEMO-D-1001} still match the case file.
     */
    public static String templateOf(String caseKey) {
        int at = caseKey == null ? -1 : caseKey.indexOf('@');
        return at < 0 ? caseKey : caseKey.substring(0, at);
    }

    private static String cloneOf(String caseKey) {
        int at = caseKey == null ? -1 : caseKey.indexOf('@');
        return at < 0 ? null : caseKey.substring(at + 1);
    }

    @Override
    public String providerName() {
        return "Recorded responses";
    }

    @Override
    public LlmResult run(LlmRequest request) {
        Recording recording = byCaseKey.get(templateOf(request.caseKey()));
        if (recording == null) {
            throw new LlmException("recorded", 0,
                    "No recorded response for case '" + request.caseKey() + "'. Available: " + byCaseKey.keySet());
        }
        int turnIndex = (int) request.messages().stream()
                .filter(m -> "assistant".equals(m.role()))
                .count();
        if (turnIndex >= recording.turns().size()) {
            throw new LlmException("recorded", 0, "Recording for case '" + request.caseKey() + "' has only "
                    + recording.turns().size() + " turn(s); the agent asked for turn " + (turnIndex + 1));
        }
        Recording.Turn turn = recording.turns().get(turnIndex);
        List<LlmRequest.ToolCall> calls = new ArrayList<>();
        if (turn.toolCalls() != null) {
            String template = templateOf(request.caseKey());
            String clone = cloneOf(request.caseKey());
            turn.toolCalls().forEach(c -> calls.add(new LlmRequest.ToolCall(c.id(), c.name(),
                    clone == null ? c.input() : rewrite(c.input(), template, clone))));
        }
        String provider = recording.provider() == null ? "Recorded responses"
                : "Recorded: " + recording.provider();
        return new LlmResult(turn.text(), calls, recording.mode(), provider, recording.model(),
                turn.inputTokens(), turn.outputTokens(), turn.latencyMs());
    }
}
