package com.disputepilot.drafts;

import com.disputepilot.agent.DisputeAgent;
import com.disputepilot.agent.DraftProposal;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.LlmException;
import com.disputepilot.agent.RetryPolicy;
import com.disputepilot.agent.EvidenceAssembler;
import com.disputepilot.agent.Playbook;
import com.disputepilot.api.DisputeRepository;
import com.disputepilot.common.ApiException;
import com.disputepilot.common.audit.AuditLogWriter;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.DraftStatus;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Money;
import com.disputepilot.domain.Offer;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import com.disputepilot.paypal.PayPalDispute;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drafting, editing and approving. The agent proposes, {@link DraftValidator} disposes, and a human approves:
 * nothing here submits anything to PayPal (that is the submission service).
 */
@Service
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    private final DisputeRepository disputes;
    private final DraftRepository drafts;
    private final EvidenceAssembler assembler;
    private final DisputeAgent agent;
    private final DraftValidator validator;
    private final AuditLogWriter audit;
    private final RecordedFallback fallback;

    /** Issue code on the placeholder saved when the provider is busy and nothing recorded can stand in. */
    public static final String AI_BUSY = "AI_BUSY";
    private static final Duration DEFAULT_BUSY_WAIT = Duration.ofMinutes(10);
    private static final DateTimeFormatter BUSY_TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'");

    /**
     * Live drafts run one at a time, so several new disputes do not hit the provider's tokens-per-minute limit
     * together. Everyone else (manual Regenerate, webhook auto-drafts) waits in this queue.
     */
    private final ExecutorService liveQueue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-draft-queue");
        t.setDaemon(true);
        return t;
    });

    public DraftService(DisputeRepository disputes, DraftRepository drafts, EvidenceAssembler assembler,
                        DisputeAgent agent, DraftValidator validator, AuditLogWriter audit,
                        RecordedFallback fallback) {
        this.disputes = disputes;
        this.drafts = drafts;
        this.assembler = assembler;
        this.agent = agent;
        this.validator = validator;
        this.audit = audit;
        this.fallback = fallback;
    }

    /** True when drafts call a real model provider (so the public-demo limits apply to them). */
    public boolean usesLiveModel() {
        return agent.llmClient().isLive();
    }

    /** Runs the agent for a dispute and stores the result as a new version, superseding any earlier draft. */
    @Transactional
    public Draft createDraft(UUID disputeId) {
        return createDraft(disputeId, true);
    }

    /**
     * Like {@link #createDraft(UUID)}; {@code allowLive=false} skips the live provider (the caller is over the
     * public demo's per-visitor limit) and goes straight to the recorded fallback.
     */
    @Transactional
    public Draft createDraft(UUID disputeId, boolean allowLive) {
        DisputeRepository.StoredDispute stored = disputes.find(disputeId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Dispute not found", "No dispute with id " + disputeId));
        PayPalDispute dispute = toPayPalDispute(stored);
        audit.user(AuditLogWriter.DRAFT_REQUESTED, disputeId,
                Map.of("paypalDisputeId", dispute.paypalDisputeId()));

        EvidenceAssembler.Assembled assembled = assembler.assemble(dispute);
        DisputeAgent.AgentOutcome outcome;
        try {
            outcome = runAgent(disputeId, dispute, assembled, allowLive);
        } catch (BusyException busy) {
            return saveBusyDraft(disputeId, dispute, busy);
        }

        if (outcome.draft() == null) {
            audit.agent(AuditLogWriter.DRAFT_FAILED, disputeId,
                    Map.of("reason", "the agent produced no draft", "turns", outcome.turns()));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "No draft produced",
                    "The agent did not return a draft after " + outcome.turns() + " turn(s).");
        }

        drafts.supersedeAll(disputeId);
        DraftProposal p = outcome.draft();
        Draft draft = new Draft(
                UUID.randomUUID(),
                disputeId,
                drafts.nextVersion(disputeId),
                outcome.validation().passed() ? DraftStatus.DRAFT : DraftStatus.NEEDS_REVIEW,
                p.recommendation() == null ? Recommendation.NEEDS_INFO : p.recommendation(),
                p.confidence(),
                p.rationale(),
                p.evidence(),
                p.notes() == null ? "" : p.notes(),
                p.citations(),
                p.missingInfo(),
                p.recommendation() == Recommendation.OFFER ? p.offer() : null,
                outcome.validation(),
                outcome.llmMode(),
                outcome.provider(),
                outcome.model(),
                outcome.promptVersion(),
                outcome.inputTokens(),
                outcome.outputTokens(),
                (int) outcome.latencyMs(),
                false,
                outcome.repairAttempted(),
                Instant.now());
        Draft saved = drafts.insert(draft);

        audit.agent(AuditLogWriter.DRAFT_CREATED, disputeId, Map.of(
                "draftId", saved.id(),
                "version", saved.version(),
                "recommendation", saved.recommendation().name(),
                "validationPassed", outcome.validation().passed(),
                "repairAttempted", outcome.repairAttempted(),
                "llmMode", saved.llmMode().dbValue(),
                "provider", String.valueOf(saved.provider()),
                "model", String.valueOf(saved.model()),
                "turns", outcome.turns()));
        log.info("Draft v{} for {}: {} (validation {}, {} turn(s), {} in / {} out tokens)",
                saved.version(), dispute.paypalDisputeId(), saved.recommendation(),
                outcome.validation().passed() ? "passed" : "failed", outcome.turns(),
                saved.inputTokens(), saved.outputTokens());
        return saved;
    }

    /**
     * One draft run. Live when the model is live and the caller may use it; when the provider answers 429 or the
     * daily cap is spent, the whole draft is re-run from the recording for that dispute (labelled as recorded),
     * or the caller gets a friendly 429 when there is no recording.
     */
    private DisputeAgent.AgentOutcome runAgent(UUID disputeId, PayPalDispute dispute,
                                               EvidenceAssembler.Assembled assembled, boolean allowLive) {
        String caseKey = caseKeyFor(dispute);
        if (!usesLiveModel()) {
            return agent.draft(caseKey, assembled);
        }
        String reason;
        Duration busyWait = DEFAULT_BUSY_WAIT;
        if (!allowLive) {
            reason = "the public demo's per-visitor limit on live drafts was reached";
        } else {
            try {
                return runQueued(() -> agent.draft(caseKey, assembled));
            } catch (LlmException e) {
                if (e.httpStatus() != 429) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI provider failed",
                            "The model provider could not draft a response: " + e.getMessage());
                }
                reason = "the model provider is rate-limited or the daily live-AI budget is spent";
                busyWait = e instanceof RetryPolicy.RateLimitedException r && r.retryAfter() != null
                        ? r.retryAfter() : DEFAULT_BUSY_WAIT;
            }
        }
        if (!fallback.covers(caseKey)) {
            throw new BusyException(reason, Instant.now().plus(busyWait));
        }
        audit.system(AuditLogWriter.LLM_FALLBACK, disputeId, Map.of("reason", reason,
                "fallback", "recorded response", "caseKey", caseKey));
        log.info("Draft for {} falls back to a recorded response: {}", dispute.paypalDisputeId(), reason);
        return fallback.agent().draft(caseKey, assembled);
    }

    /** Runs a live agent call on the single-threaded queue and hands back its result or its original exception. */
    private <T> T runQueued(java.util.concurrent.Callable<T> call) {
        try {
            return liveQueue.submit(call).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("queue", 0, "interrupted while waiting for the live-draft queue");
        }
    }

    /** No live answer and no recording: nothing to show but an honest "busy" state the merchant can retry. */
    private static final class BusyException extends RuntimeException {
        final String reason;
        final Instant retryAt;

        BusyException(String reason, Instant retryAt) {
            super(reason);
            this.reason = reason;
            this.retryAt = retryAt;
        }
    }

    private Draft saveBusyDraft(UUID disputeId, PayPalDispute dispute, BusyException busy) {
        String message = "AI is busy, try again around " + BUSY_TIME.format(busy.retryAt.atOffset(ZoneOffset.UTC))
                + ". Live drafting is paused because " + busy.reason + ". Use Regenerate to try again.";
        drafts.supersedeAll(disputeId);
        Draft saved = drafts.insert(new Draft(UUID.randomUUID(), disputeId, drafts.nextVersion(disputeId),
                DraftStatus.NEEDS_REVIEW, Recommendation.NEEDS_INFO, BigDecimal.ZERO, message, List.of(), "",
                List.of(), List.of(), null,
                new ValidationResult(false, List.of(new ValidationResult.Issue(AI_BUSY, message, null))),
                com.disputepilot.domain.LlmMode.LIVE, null, null, null, null, null, 0, false, false,
                Instant.now()));
        audit.system(AuditLogWriter.LLM_FALLBACK, disputeId, Map.of("reason", busy.reason,
                "fallback", "AI busy placeholder", "retryAt", busy.retryAt.toString()));
        log.info("Draft for {} saved as 'AI is busy': {}", dispute.paypalDisputeId(), busy.reason);
        return saved;
    }

    public Optional<Draft> latest(UUID disputeId) {
        return drafts.findLatest(disputeId);
    }

    public Optional<Draft> find(UUID draftId) {
        return drafts.find(draftId);
    }

    /** A human edit to notes and/or evidence. Re-validates and marks the draft as human-edited. */
    @Transactional
    public Draft edit(UUID draftId, String notes, List<EvidenceItem> evidence) {
        return edit(draftId, notes, evidence, null);
    }

    /** A human edit to notes, evidence and/or the offer. Re-validates and marks the draft as human-edited. */
    @Transactional
    public Draft edit(UUID draftId, String notes, List<EvidenceItem> evidence, Offer offer) {
        Draft existing = require(draftId);
        if (existing.status() == DraftStatus.SUBMITTED || existing.status() == DraftStatus.SUPERSEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft is not editable",
                    "Draft " + draftId + " is " + existing.status() + " and can no longer be edited.");
        }
        String newNotes = notes == null ? existing.notes() : notes;
        List<EvidenceItem> newEvidence = evidence == null ? existing.evidence() : evidence;
        Offer newOffer = offer == null ? existing.offer()
                : offer.withCurrencyIfMissing(existing.offer() == null || existing.offer().amount() == null
                        ? null : existing.offer().amount().currency());

        ValidationResult validation = revalidate(existing, newNotes, newEvidence, newOffer);
        DraftStatus status = validation.passed() ? DraftStatus.DRAFT : DraftStatus.NEEDS_REVIEW;
        Draft saved = drafts.updateEdited(draftId, newNotes, newEvidence, newOffer, validation, status);

        audit.user(AuditLogWriter.DRAFT_EDITED, existing.disputeId(), Map.of(
                "draftId", draftId,
                "notesChanged", notes != null,
                "evidenceChanged", evidence != null,
                "offerChanged", offer != null,
                "validationPassed", validation.passed()));
        return saved;
    }

    /** Approves a draft. Validation must have passed, unless the merchant explicitly overrides. */
    @Transactional
    public Draft approve(UUID draftId, boolean overrideValidation) {
        Draft existing = require(draftId);
        if (existing.status() == DraftStatus.SUBMITTED || existing.status() == DraftStatus.SUPERSEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft cannot be approved",
                    "Draft " + draftId + " is " + existing.status() + ".");
        }
        if (existing.validation().issues().stream().anyMatch(i -> AI_BUSY.equals(i.code()))) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft cannot be approved",
                    "This is an \"AI is busy\" placeholder, not a draft. Use Regenerate once the AI is available.");
        }
        if (!existing.validation().passed() && !overrideValidation) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft did not pass validation",
                    "This draft has " + existing.validation().issues().size() + " open validation issue(s). "
                            + "Fix them, or approve again with overrideValidation=true to accept them.");
        }
        Draft saved = drafts.updateStatus(draftId, DraftStatus.APPROVED);
        audit.user(AuditLogWriter.DRAFT_APPROVED, existing.disputeId(), Map.of(
                "draftId", draftId,
                "version", saved.version(),
                "overrodeValidation", overrideValidation && !existing.validation().passed()));
        return saved;
    }

    /** Re-runs the grounding checks against the live case file after a human edit. */
    private ValidationResult revalidate(Draft draft, String notes, List<EvidenceItem> evidence, Offer offer) {
        return disputes.find(draft.disputeId())
                .map(stored -> {
                    EvidenceAssembler.Assembled a = assembler.assemble(toPayPalDispute(stored));
                    String currency = stored.summary().amount() == null ? null : stored.summary().amount().currency();
                    DraftProposal proposal = new DraftProposal(draft.recommendation(), draft.confidence(),
                            draft.rationale(), evidence, notes, draft.citations(), draft.missingInfo(),
                            offer == null ? null : offer.withCurrencyIfMissing(currency));
                    return validator.validate(proposal, a.caseFile(),
                            Playbook.forReason(a.caseFile().dispute().reason()), a.redactor());
                })
                .orElse(draft.validation());
    }

    private Draft require(UUID draftId) {
        return drafts.find(draftId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Draft not found", "No draft with id " + draftId));
    }

    /**
     * The key a recorded response is stored under. The dispute's own PayPal ID: the starter fixtures and the
     * demo templates are {@code DEMO-…} disputes, and every eval case is recorded under the ID of the dispute
     * it describes, so recorded mode covers the whole demo.
     */
    public static String caseKeyFor(PayPalDispute dispute) {
        // A simulated dispute created from a template is a clone under a new ID: replay the template's
        // recording with the ID rewritten ("template@clone", see RecordedLlmClient).
        String template = dispute.raw() == null ? null : dispute.raw().path("_demo_template").asString(null);
        if (template != null && !template.isBlank() && !template.equals(dispute.paypalDisputeId())) {
            return template + "@" + dispute.paypalDisputeId();
        }
        return dispute.paypalDisputeId();
    }

    /** The stored dispute, back in the shape the assembler and the agent work with. */
    static PayPalDispute toPayPalDispute(DisputeRepository.StoredDispute stored) {
        var s = stored.summary();
        Money amount = s.amount();
        return new PayPalDispute(
                s.paypalDisputeId(),
                s.simulated(),
                s.reason(),
                s.status(),
                s.stage(),
                amount,
                s.sellerResponseDueAt(),
                stored.disputedTransactionId(),
                stored.requestedEvidence(),
                stored.allowedActions(),
                stored.raw().path("dispute_outcome").path("outcome_code").asString(null),
                null,
                stored.raw());
    }
}
