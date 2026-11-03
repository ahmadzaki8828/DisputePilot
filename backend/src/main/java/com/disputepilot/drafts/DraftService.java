package com.disputepilot.drafts;

import com.disputepilot.agent.DisputeAgent;
import com.disputepilot.agent.DraftProposal;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.EvidenceAssembler;
import com.disputepilot.agent.Playbook;
import com.disputepilot.api.DisputeRepository;
import com.disputepilot.common.ApiException;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.DraftStatus;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Money;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import com.disputepilot.paypal.PayPalDispute;
import java.time.Instant;
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
    private final DraftAudit audit;

    public DraftService(DisputeRepository disputes, DraftRepository drafts, EvidenceAssembler assembler,
                        DisputeAgent agent, DraftValidator validator, DraftAudit audit) {
        this.disputes = disputes;
        this.drafts = drafts;
        this.assembler = assembler;
        this.agent = agent;
        this.validator = validator;
        this.audit = audit;
    }

    /** Runs the agent for a dispute and stores the result as a new version, superseding any earlier draft. */
    @Transactional
    public Draft createDraft(UUID disputeId) {
        DisputeRepository.StoredDispute stored = disputes.find(disputeId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Dispute not found", "No dispute with id " + disputeId));
        PayPalDispute dispute = toPayPalDispute(stored);
        audit.record("user", DraftAudit.DRAFT_REQUESTED, disputeId,
                Map.of("paypalDisputeId", dispute.paypalDisputeId()));

        EvidenceAssembler.Assembled assembled = assembler.assemble(dispute);
        DisputeAgent.AgentOutcome outcome = agent.draft(caseKeyFor(dispute), assembled);

        if (outcome.draft() == null) {
            audit.record("agent", DraftAudit.DRAFT_FAILED, disputeId,
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

        audit.record("agent", DraftAudit.DRAFT_CREATED, disputeId, Map.of(
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

    public Optional<Draft> latest(UUID disputeId) {
        return drafts.findLatest(disputeId);
    }

    public Optional<Draft> find(UUID draftId) {
        return drafts.find(draftId);
    }

    /** A human edit to notes and/or evidence. Re-validates and marks the draft as human-edited. */
    @Transactional
    public Draft edit(UUID draftId, String notes, List<EvidenceItem> evidence) {
        Draft existing = require(draftId);
        if (existing.status() == DraftStatus.SUBMITTED || existing.status() == DraftStatus.SUPERSEDED) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft is not editable",
                    "Draft " + draftId + " is " + existing.status() + " and can no longer be edited.");
        }
        String newNotes = notes == null ? existing.notes() : notes;
        List<EvidenceItem> newEvidence = evidence == null ? existing.evidence() : evidence;

        ValidationResult validation = revalidate(existing, newNotes, newEvidence);
        DraftStatus status = validation.passed() ? DraftStatus.DRAFT : DraftStatus.NEEDS_REVIEW;
        Draft saved = drafts.updateEdited(draftId, newNotes, newEvidence, validation, status);

        audit.record("user", DraftAudit.DRAFT_EDITED, existing.disputeId(), Map.of(
                "draftId", draftId,
                "notesChanged", notes != null,
                "evidenceChanged", evidence != null,
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
        if (!existing.validation().passed() && !overrideValidation) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft did not pass validation",
                    "This draft has " + existing.validation().issues().size() + " open validation issue(s). "
                            + "Fix them, or approve again with overrideValidation=true to accept them.");
        }
        Draft saved = drafts.updateStatus(draftId, DraftStatus.APPROVED);
        audit.record("user", DraftAudit.DRAFT_APPROVED, existing.disputeId(), Map.of(
                "draftId", draftId,
                "version", saved.version(),
                "overrodeValidation", overrideValidation && !existing.validation().passed()));
        return saved;
    }

    /** Re-runs the grounding checks against the live case file after a human edit. */
    private ValidationResult revalidate(Draft draft, String notes, List<EvidenceItem> evidence) {
        return disputes.find(draft.disputeId())
                .map(stored -> {
                    EvidenceAssembler.Assembled a = assembler.assemble(toPayPalDispute(stored));
                    DraftProposal proposal = new DraftProposal(draft.recommendation(), draft.confidence(),
                            draft.rationale(), evidence, notes, draft.citations(), draft.missingInfo(), null);
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
