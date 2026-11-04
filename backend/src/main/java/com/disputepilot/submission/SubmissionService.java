package com.disputepilot.submission;

import com.disputepilot.api.DisputeRepository;
import com.disputepilot.common.ApiException;
import com.disputepilot.common.audit.AuditLogWriter;
import com.disputepilot.domain.Draft;
import com.disputepilot.domain.DraftStatus;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.SubmissionAction;
import com.disputepilot.drafts.DraftRepository;
import com.disputepilot.paypal.AcceptClaimRequest;
import com.disputepilot.paypal.EvidenceSubmission;
import com.disputepilot.paypal.PayPalApiException;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.PayPalDisputesClient;
import com.disputepilot.sync.DisputeSyncService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends an approved draft to PayPal (plan §13 D.2). Nothing is ever submitted without
 *
 * <ol>
 *   <li>an {@code Idempotency-Key} - the same key returns the stored result and never calls PayPal twice;</li>
 *   <li>an {@code APPROVED} draft - a human clicked approve;</li>
 *   <li>the matching action in the {@code links[]} of a dispute fetched from PayPal <em>just now</em>.</li>
 * </ol>
 *
 * <p>The service is deliberately not transactional: a database transaction held across a PayPal call would hide
 * the PENDING row from a concurrent duplicate, which is exactly the request the key exists to stop.
 */
@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);
    static final int MIN_KEY = 8;
    static final int MAX_KEY = 128;
    private static final int MAX_NOTE = 2000;

    private final DraftRepository drafts;
    private final DisputeRepository disputes;
    private final SubmissionRepository submissions;
    private final PayPalDisputesClient paypal;
    private final DisputeSyncService sync;
    private final AuditLogWriter audit;
    private final JsonMapper json;

    public SubmissionService(DraftRepository drafts, DisputeRepository disputes, SubmissionRepository submissions,
                             PayPalDisputesClient paypal, DisputeSyncService sync, AuditLogWriter audit,
                             JsonMapper json) {
        this.drafts = drafts;
        this.disputes = disputes;
        this.submissions = submissions;
        this.paypal = paypal;
        this.sync = sync;
        this.audit = audit;
        this.json = json;
    }

    /** The stored or fresh result, and whether it was served from an earlier call with the same key. */
    public record Result(Submission submission, boolean replayed) {
    }

    public Result submit(UUID draftId, String idempotencyKey, String submittedBy) {
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.length() < MIN_KEY || key.length() > MAX_KEY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key required",
                    "Send an Idempotency-Key header of " + MIN_KEY + " to " + MAX_KEY + " characters (a UUID is "
                            + "ideal). The same key returns the stored result instead of calling PayPal again.");
        }

        Draft draft = drafts.find(draftId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Draft not found", "No draft with id " + draftId));

        Optional<Submission> existing = submissions.findByKey(key);
        UUID retryOf = null;
        if (existing.isPresent()) {
            Submission prior = existing.get();
            if (!prior.draftId().equals(draftId)) {
                throw new ApiException(HttpStatus.CONFLICT, "Idempotency-Key already used",
                        "That Idempotency-Key belongs to a different draft. Use a fresh key for each draft.");
            }
            switch (prior.status()) {
                case SUCCEEDED -> {
                    audit.user(AuditLogWriter.SUBMISSION_REPLAYED, draft.disputeId(),
                            Map.of("draftId", draftId, "idempotencyKey", key, "submissionId", prior.id()));
                    return new Result(prior, true);
                }
                case PENDING -> throw new ApiException(HttpStatus.CONFLICT, "Submission in progress",
                        "A request with this Idempotency-Key is still being sent to PayPal. Retry in a moment.");
                case FAILED -> retryOf = prior.id(); // the PayPal call failed; the same key may try again
            }
        }

        if (draft.status() != DraftStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "Draft is not approved",
                    "Only an APPROVED draft can be submitted; this one is " + draft.status() + ".");
        }
        SubmissionAction action = actionFor(draft);

        DisputeRepository.StoredDispute stored = disputes.find(draft.disputeId()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Dispute not found", "The draft's dispute no longer exists."));
        String paypalId = stored.summary().paypalDisputeId();

        // PayPal is the source of truth: re-fetch right now and require the action in its links[].
        PayPalDispute fresh = sync.sync(paypalId, "PRE_SUBMIT").dispute();
        if (!fresh.allowedActions().contains(action.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "Action not allowed by PayPal",
                    "PayPal does not currently allow " + action + " on this dispute (status " + fresh.status()
                            + ", allowed: " + fresh.allowedActions() + "). It may already have been answered.");
        }

        Map<String, Object> request = requestPayload(action, draft);
        String requestJson = json.writeValueAsString(request);
        UUID submissionId;
        if (retryOf != null) {
            if (!submissions.reclaimFailed(retryOf, requestJson)) {
                throw new ApiException(HttpStatus.CONFLICT, "Submission in progress",
                        "This draft has another submission in flight or already sent.");
            }
            submissionId = retryOf;
        } else {
            submissionId = submissions.claim(draftId, key, action, requestJson, submittedBy).orElseThrow(() -> {
                // Lost the race: either the same key raced us, or another key already holds this draft.
                Optional<Submission> raced = submissions.findByKey(key);
                return raced.isPresent()
                        ? new ApiException(HttpStatus.CONFLICT, "Submission in progress",
                                "A request with this Idempotency-Key is already being processed.")
                        : new ApiException(HttpStatus.CONFLICT, "Draft already submitted",
                                "This draft already has a submission in flight or sent under another key.");
            });
        }
        audit.user(AuditLogWriter.SUBMISSION_STARTED, draft.disputeId(), Map.of(
                "draftId", draftId, "submissionId", submissionId, "idempotencyKey", key,
                "action", action.name(), "paypalDisputeId", paypalId));

        try {
            send(paypalId, action, draft);
        } catch (PayPalApiException e) {
            log.warn("Submission {} for {} failed at PayPal: {} {} debug_id={}", submissionId, paypalId,
                    e.httpStatus(), e.name(), e.debugId());
            submissions.markFailed(submissionId, json.writeValueAsString(Map.of("name", String.valueOf(e.name()),
                    "message", String.valueOf(e.getMessage()), "httpStatus", e.httpStatus(),
                    "issues", e.issues())), e.debugId());
            audit.user(AuditLogWriter.SUBMISSION_FAILED, draft.disputeId(), failureDetails(submissionId, key,
                    String.valueOf(e.name()), e.debugId()));
            throw e;
        } catch (RuntimeException e) {
            submissions.markFailed(submissionId, json.writeValueAsString(Map.of("message",
                    String.valueOf(e.getMessage()))), null);
            audit.user(AuditLogWriter.SUBMISSION_FAILED, draft.disputeId(), failureDetails(submissionId, key,
                    e.getClass().getSimpleName(), null));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Submission failed",
                    "The request to PayPal failed before an answer came back (" + e.getMessage() + "). It is safe "
                            + "to retry with the same Idempotency-Key: DisputePilot re-checks PayPal first.");
        }

        submissions.markSucceeded(submissionId, json.writeValueAsString(Map.of("accepted", true,
                "action", action.name())));
        drafts.updateStatus(draftId, DraftStatus.SUBMITTED);
        audit.user(AuditLogWriter.SUBMISSION_SUCCEEDED, draft.disputeId(), Map.of(
                "draftId", draftId, "submissionId", submissionId, "idempotencyKey", key,
                "action", action.name(), "paypalDisputeId", paypalId));
        try {
            sync.sync(paypalId, "SUBMITTED"); // pick up the new status; the submission itself already went through
        } catch (RuntimeException e) {
            log.warn("Submitted {} but the follow-up sync failed: {}", paypalId, e.getMessage());
        }
        return new Result(submissions.find(submissionId).orElseThrow(), false);
    }

    /** FIGHT answers with evidence, ACCEPT accepts the claim. OFFER and NEEDS_INFO have no PayPal call here. */
    static SubmissionAction actionFor(Draft draft) {
        Recommendation r = draft.recommendation();
        if (r == Recommendation.ACCEPT) {
            return SubmissionAction.ACCEPT_CLAIM;
        }
        if (r == Recommendation.FIGHT) {
            if (draft.evidence() == null || draft.evidence().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "No evidence to submit",
                        "This draft recommends fighting but carries no evidence.");
            }
            return SubmissionAction.PROVIDE_EVIDENCE;
        }
        throw new ApiException(HttpStatus.CONFLICT, "Recommendation cannot be submitted here",
                "This draft recommends " + r + ". DisputePilot submits evidence (FIGHT) or accepts a claim "
                        + "(ACCEPT); an offer needs an amount the merchant sets in PayPal.");
    }

    private void send(String paypalId, SubmissionAction action, Draft draft) {
        switch (action) {
            case PROVIDE_EVIDENCE -> paypal.provideEvidence(paypalId, new EvidenceSubmission(evidences(draft)));
            case ACCEPT_CLAIM -> paypal.acceptClaim(paypalId,
                    new AcceptClaimRequest(truncate(draft.notes()), "REASON_NOT_SET"));
            default -> throw new IllegalStateException("Unsupported action " + action);
        }
    }

    /** The evidence PayPal receives: each item keeps its own notes, and falls back to the draft's note. */
    static List<EvidenceItem> evidences(Draft draft) {
        List<EvidenceItem> out = new ArrayList<>();
        for (EvidenceItem e : draft.evidence()) {
            String notes = e.notes() == null || e.notes().isBlank() ? truncate(draft.notes()) : truncate(e.notes());
            out.add(new EvidenceItem(e.evidenceType(), e.evidenceInfo(), notes));
        }
        return out;
    }

    private Map<String, Object> requestPayload(SubmissionAction action, Draft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.name());
        if (action == SubmissionAction.PROVIDE_EVIDENCE) {
            payload.put("evidences", evidences(draft));
        } else {
            payload.put("note", truncate(draft.notes()));
            payload.put("accept_claim_reason", "REASON_NOT_SET");
        }
        return payload;
    }

    private static Map<String, Object> failureDetails(UUID submissionId, String key, String error, String debugId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("submissionId", submissionId);
        d.put("idempotencyKey", key);
        d.put("error", error);
        if (debugId != null) {
            d.put("paypalDebugId", debugId);
        }
        return d;
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() <= MAX_NOTE ? s : s.substring(0, MAX_NOTE);
    }
}
