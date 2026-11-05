package com.disputepilot.api;

import com.disputepilot.common.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/evals/latest}: the committed {@code evals/report.json}, reshaped into the contract's EvalReport.
 * Read from {@code evals/report.json} when running from a checkout, otherwise from the copy packaged in the jar.
 */
@RestController
public class EvalReportController {

    private final JsonMapper json;

    public EvalReportController(JsonMapper json) {
        this.json = json;
    }

    @GetMapping("/api/evals/latest")
    public Map<String, Object> latest() {
        return toContract(load());
    }

    private JsonNode load() {
        try {
            for (Path candidate : List.of(Path.of("evals/report.json"), Path.of("../evals/report.json"))) {
                if (Files.isRegularFile(candidate)) {
                    return json.readTree(Files.readString(candidate));
                }
            }
            ClassPathResource packaged = new ClassPathResource("evals/report.json");
            if (packaged.exists()) {
                try (InputStream in = packaged.getInputStream()) {
                    return json.readTree(in);
                }
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Eval report unreadable", e.getMessage());
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "No eval report",
                "evals/report.json does not exist yet. Run ./mvnw -B -Peval test in backend/.");
    }

    static Map<String, Object> toContract(JsonNode report) {
        JsonNode s = report.path("summary");
        int cases = s.path("cases").asInt(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", report.path("mode").asString("fake"));
        out.put("generatedAt", report.path("generatedAt").asString(null));
        out.put("provider", report.path("provider").asString(null));
        out.put("model", report.path("model").asString(null));
        out.put("promptVersion", report.path("promptVersion").asString(null));
        out.put("cases", cases);
        out.put("recommendationAccuracy", ratio(s.path("recommendationCorrect").asInt(0), cases));
        out.put("evidenceTypeAccuracy", ratio(s.path("evidenceTypeCorrect").asInt(0), cases));
        out.put("groundingPassRate", ratio(s.path("groundingPassed").asInt(0), cases));
        out.put("injectionResistance", ratio(s.path("injectionResisted").asInt(0), s.path("injectionCases").asInt(0)));
        out.put("meanInputTokens", s.path("meanInputTokens").asDouble(0));
        out.put("meanOutputTokens", s.path("meanOutputTokens").asDouble(0));
        out.put("meanLatencyMs", s.path("meanLatencyMs").asDouble(0));
        // The report says "not priced" (a string) unless both token prices were set; never invent a number.
        JsonNode cost = s.path("estimatedCostPerDraft");
        out.put("estimatedCostPerDraftUsd", cost.isNumber() ? cost.asDouble() : null);
        out.put("offerAccuracy", ratio(s.path("offerCorrect").asInt(cases), cases));
        if (report.path("splits").isObject()) {
            Map<String, Object> splits = new LinkedHashMap<>();
            report.path("splits").properties().forEach(e -> splits.put(e.getKey(), splitSummary(e.getValue())));
            out.put("splits", splits);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode c : report.path("cases")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("caseId", c.path("name").asString(c.path("caseKey").asString("")));
            List<String> expected = new ArrayList<>();
            c.path("expectedRecommendation").forEach(n -> expected.add(n.asString()));
            r.put("expectedRecommendation", String.join(" or ", expected));
            r.put("actualRecommendation", c.path("recommendation").asString(""));
            r.put("recommendationCorrect", c.path("recommendationOk").asBoolean(false));
            r.put("evidenceTypesCorrect", c.path("evidenceOk").asBoolean(false));
            r.put("grounded", c.path("groundingPassed").asBoolean(false));
            r.put("injectionResisted", c.path("injectionAttempt").asBoolean(false)
                    ? (Object) c.path("injectionResisted").asBoolean(false) : null);
            r.put("split", c.path("split").asString("tuned"));
            r.put("offer", c.path("offer").asString(null));
            r.put("offerCorrect", c.path("offerOk").asBoolean(true));
            results.add(r);
        }
        out.put("results", results);
        return out;
    }

    private static Map<String, Object> splitSummary(JsonNode s) {
        int cases = s.path("cases").asInt(0);
        int injectionCases = s.path("injectionCases").asInt(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", cases);
        out.put("recommendationAccuracy", ratio(s.path("recommendationCorrect").asInt(0), cases));
        out.put("evidenceTypeAccuracy", ratio(s.path("evidenceTypeCorrect").asInt(0), cases));
        out.put("offerAccuracy", ratio(s.path("offerCorrect").asInt(cases), cases));
        out.put("groundingPassRate", ratio(s.path("groundingPassed").asInt(0), cases));
        out.put("injectionResistance", injectionCases == 0 ? null
                : (Object) ratio(s.path("injectionResisted").asInt(0), injectionCases));
        return out;
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 1.0 : (double) part / whole;
    }
}
