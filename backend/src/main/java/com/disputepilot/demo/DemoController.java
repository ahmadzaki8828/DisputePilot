package com.disputepilot.demo;

import com.disputepilot.api.DisputeSummary;
import com.disputepilot.common.ApiException;
import com.disputepilot.common.ClientIp;
import com.disputepilot.common.limits.DemoLimits;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/demo/disputes} and {@code POST /api/demo/reset}: the judges' controls, rate-limited per client. */
@RestController
public class DemoController {

    public record CreateRequest(String template) {
    }

    private final DemoService demo;
    private final DemoLimits limits;

    public DemoController(DemoService demo, DemoLimits limits) {
        this.demo = demo;
        this.limits = limits;
    }

    @PostMapping("/api/demo/disputes")
    public ResponseEntity<DisputeSummary> create(@RequestBody(required = false) CreateRequest body,
                                                 HttpServletRequest request) {
        throttle(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(demo.createDispute(body == null ? null : body.template()));
    }

    @PostMapping("/api/demo/reset")
    public Map<String, Object> reset(HttpServletRequest request) {
        throttle(request);
        return Map.of("seeded", demo.reset());
    }

    private void throttle(HttpServletRequest request) {
        if (!limits.tryAction(ClientIp.of(request))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demo limit reached",
                    "This public demo allows a limited number of simulate/reset actions per visitor per hour. "
                            + "Please try again later.");
        }
    }
}
