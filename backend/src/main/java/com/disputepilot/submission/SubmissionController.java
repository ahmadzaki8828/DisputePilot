package com.disputepilot.submission;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/drafts/{id}/submit} (plan §7). The {@code Idempotency-Key} header is required. */
@RestController
public class SubmissionController {

    private final SubmissionService service;

    public SubmissionController(SubmissionService service) {
        this.service = service;
    }

    @PostMapping("/api/drafts/{id}/submit")
    public ResponseEntity<Submission> submit(@PathVariable UUID id,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        SubmissionService.Result result = service.submit(id, key, "user");
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (result.replayed()) {
            ok.header("Idempotent-Replayed", "true");
        }
        return ok.body(result.submission());
    }
}
