package com.disputepilot.api;

import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.RecordedLlmClient;
import com.disputepilot.agent.Recording;
import com.disputepilot.common.AppProperties;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/system}: the modes the dashboard shows as badges, so nothing is passed off as something else. */
@RestController
public class SystemController {

    /** @param llmMode live (a provider is called), recorded (real responses replayed) or sample (hand-written) */
    public record SystemInfo(String paypalMode, String llmMode, String model, String provider,
                             boolean simulatedEnabled) {
    }

    private final AppProperties props;
    private final LlmClient llm;

    public SystemController(AppProperties props, LlmClient llm) {
        this.props = props;
        this.llm = llm;
    }

    @GetMapping("/api/system")
    public SystemInfo system() {
        String paypalMode = props.paypal().mode().name().toLowerCase();
        if (llm.isLive()) {
            return new SystemInfo(paypalMode, "live", props.llm().effectiveModel(), llm.providerName(), true);
        }
        if (llm instanceof RecordedLlmClient recorded && !recorded.recordings().isEmpty()) {
            boolean allSamples = recorded.recordings().stream().allMatch(r -> "sample".equalsIgnoreCase(r.source()));
            // The model that produced most of the recordings; a sample set has no model behind it.
            String model = allSamples ? "" : recorded.recordings().stream().map(Recording::model)
                    .filter(m -> m != null && !m.isBlank())
                    .collect(Collectors.groupingBy(m -> m, Collectors.counting())).entrySet().stream()
                    .max(Comparator.comparingLong(java.util.Map.Entry::getValue))
                    .map(java.util.Map.Entry::getKey).orElse("");
            String provider = allSamples ? "Hand-written samples" : recorded.recordings().stream()
                    .map(Recording::provider).filter(p -> p != null && !p.isBlank()).findFirst()
                    .map(p -> "Recorded: " + p).orElse("Recorded responses");
            return new SystemInfo(paypalMode, allSamples ? "sample" : "recorded", model, provider, true);
        }
        return new SystemInfo(paypalMode, "sample", "", "Hand-written samples", true);
    }
}
