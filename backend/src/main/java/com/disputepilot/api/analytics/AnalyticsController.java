package com.disputepilot.api.analytics;

import com.disputepilot.common.ApiException;
import com.disputepilot.common.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The AG Studio analytics page (plan §13, Session F).
 *
 * <ul>
 *   <li>{@code GET /api/analytics/dataset}: read-only tables of disputes, drafts and submissions for the dashboard,
 *       plus whether the dashboard agent is available and which provider and model it uses.</li>
 *   <li>{@code POST /api/analytics/agent}: one dashboard-agent turn, sent to the app's configured model provider.
 *       It only reads and writes the dashboard layout in the browser; it never touches disputes.</li>
 * </ul>
 */
@RestController
public class AnalyticsController {

    public record Dataset(String asOf, List<AnalyticsRepository.DisputeRow> disputes,
                          List<AnalyticsRepository.DraftRow> drafts,
                          List<AnalyticsRepository.SubmissionRow> submissions,
                          AnalyticsAgentProxy.AgentInfo agent) {
    }

    private final AnalyticsRepository repository;
    private final AnalyticsAgentProxy agent;
    private final Clock clock = Clock.systemUTC();

    public AnalyticsController(AnalyticsRepository repository, AnalyticsAgentProxy agent) {
        this.repository = repository;
        this.agent = agent;
    }

    @GetMapping("/api/analytics/dataset")
    public Dataset dataset() {
        Instant now = clock.instant();
        return new Dataset(now.toString(), repository.disputes(now), repository.drafts(), repository.submissions(),
                agent.info());
    }

    @PostMapping(path = "/api/analytics/agent", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode turn(@RequestBody(required = false) String body, HttpServletRequest request) {
        try {
            return agent.turn(body, ClientIp.of(request));
        } catch (AnalyticsAgentProxy.AgentException e) {
            HttpStatus status = HttpStatus.valueOf(e.status());
            throw new ApiException(status, status.getReasonPhrase(), e.getMessage());
        }
    }
}
