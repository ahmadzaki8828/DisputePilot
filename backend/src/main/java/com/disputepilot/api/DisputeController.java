package com.disputepilot.api;

import com.disputepilot.common.ApiException;
import com.disputepilot.merchant.MerchantDataPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/disputes")
public class DisputeController {

    private final DisputeRepository repo;
    private final MerchantDataPort merchant;

    public DisputeController(DisputeRepository repo, MerchantDataPort merchant) {
        this.repo = repo;
        this.merchant = merchant;
    }

    @GetMapping
    public List<DisputeSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) Integer dueWithinHours,
            @RequestParam(required = false) Boolean simulated) {
        return repo.list(status, reason, dueWithinHours, simulated);
    }

    @GetMapping("/{id}")
    public DisputeDetail get(@PathVariable UUID id) {
        DisputeRepository.StoredDispute d = repo.find(id).orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "Dispute not found", "No dispute with id " + id));
        DisputeSummary s = d.summary();

        List<DisputeDetail.DisputeMessage> messages = new ArrayList<>();
        d.raw().path("messages").forEach(m -> {
            String t = m.path("time_posted").asString(null);
            messages.add(new DisputeDetail.DisputeMessage(m.path("posted_by").asString(),
                    t == null ? null : Instant.parse(t), m.path("content").asString()));
        });

        var order = merchant.findOrderByPaypalTransactionId(d.disputedTransactionId()).orElse(null);
        var caseFile = new DisputeDetail.CaseFile(
                order,
                order == null ? List.of() : merchant.shipmentsForOrder(order.id()),
                order == null ? List.of() : merchant.messagesForOrder(order.id()),
                merchant.policy());

        return new DisputeDetail(s.id(), s.paypalDisputeId(), s.simulated(), s.reason(), s.reasonLabel(), s.status(),
                s.stage(), s.amount(), s.sellerResponseDueAt(), s.latestDraft(), d.requestedEvidence(),
                d.allowedActions(), messages, caseFile);
    }
}
