package com.disputepilot.drafts;

import com.disputepilot.common.ApiException;
import com.disputepilot.common.ClientIp;
import com.disputepilot.common.limits.DemoLimits;
import jakarta.servlet.http.HttpServletRequest;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Offer;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The draft endpoints from plan §7. Rate limits and the daily LLM cap are wired in by Session D. */
@RestController
public class DraftController {

    private final DraftService drafts;
    private final DemoLimits limits;

    public DraftController(DraftService drafts, DemoLimits limits) {
        this.drafts = drafts;
        this.limits = limits;
    }

    /** What a human may change on a draft. */
    public record DraftEdit(String notes, List<EvidenceItem> evidence, Offer offer) {
    }

    public record ApproveRequest(Boolean overrideValidation) {
    }

    @PostMapping("/api/disputes/{id}/drafts")
    public ResponseEntity<Draft> create(@PathVariable UUID id, HttpServletRequest request) {
        // Only live drafts cost money, so only they count against the visitor's hourly allowance (plan §9.2).
        boolean allowLive = !drafts.usesLiveModel() || limits.tryDraft(ClientIp.of(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(drafts.createDraft(id, allowLive));
    }

    @GetMapping("/api/disputes/{id}/drafts/latest")
    public Draft latest(@PathVariable UUID id) {
        return drafts.latest(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No draft",
                "Dispute " + id + " has no draft yet."));
    }

    @PatchMapping("/api/drafts/{id}")
    public Draft edit(@PathVariable UUID id, @RequestBody(required = false) DraftEdit edit) {
        DraftEdit body = edit == null ? new DraftEdit(null, null, null) : edit;
        return drafts.edit(id, body.notes(), body.evidence(), body.offer());
    }

    @PostMapping("/api/drafts/{id}/approve")
    public Draft approve(@PathVariable UUID id, @RequestBody(required = false) ApproveRequest body) {
        return drafts.approve(id, body != null && Boolean.TRUE.equals(body.overrideValidation()));
    }
}
