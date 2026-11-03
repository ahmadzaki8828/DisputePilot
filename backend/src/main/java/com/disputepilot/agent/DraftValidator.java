package com.disputepilot.agent;

import com.disputepilot.domain.Citation;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic grounding checks (plan §9.2 step 5). The model proposes; this code disposes. Nothing reaches a
 * merchant marked ready unless every claim in the draft traces back to a record in the case file.
 *
 * <p>Each failure carries a stable code, a message the merchant can read, and the JSON path it applies to, so
 * the UI can point at the offending field and the agent can be asked to repair it.
 */
@Component
public class DraftValidator {

    public static final String EVIDENCE_TYPE_NOT_ALLOWED = "EVIDENCE_TYPE_NOT_ALLOWED";
    public static final String EVIDENCE_TYPE_NOT_REQUESTED = "EVIDENCE_TYPE_NOT_REQUESTED";
    public static final String EVIDENCE_MISSING_REQUIRED_FIELD = "EVIDENCE_MISSING_REQUIRED_FIELD";
    public static final String UNKNOWN_TRACKING_NUMBER = "UNKNOWN_TRACKING_NUMBER";
    public static final String UNKNOWN_REFUND_ID = "UNKNOWN_REFUND_ID";
    public static final String UNKNOWN_CITATION = "UNKNOWN_CITATION";
    public static final String NO_CITATIONS = "NO_CITATIONS";
    public static final String REDACTION_TOKEN_IN_TEXT = "REDACTION_TOKEN_IN_TEXT";
    public static final String PII_IN_TEXT = "PII_IN_TEXT";
    public static final String OFFER_EXCEEDS_DISPUTED_AMOUNT = "OFFER_EXCEEDS_DISPUTED_AMOUNT";
    public static final String OFFER_WITHOUT_AMOUNT = "OFFER_WITHOUT_AMOUNT";
    public static final String FIGHT_WITHOUT_EVIDENCE = "FIGHT_WITHOUT_EVIDENCE";
    public static final String NOTES_TOO_LONG = "NOTES_TOO_LONG";
    public static final String RATIONALE_TOO_LONG = "RATIONALE_TOO_LONG";
    public static final String CONFIDENCE_OUT_OF_RANGE = "CONFIDENCE_OUT_OF_RANGE";
    public static final String NOTES_MISSING = "NOTES_MISSING";

    public static final int MAX_NOTES = 2000;
    public static final int MAX_RATIONALE = 600;

    public ValidationResult validate(DraftProposal draft, CaseFile caseFile, Playbook playbook,
                                     PiiRedactor redactor) {
        List<ValidationResult.Issue> issues = new ArrayList<>();
        if (draft == null) {
            issues.add(new ValidationResult.Issue("NO_DRAFT", "The agent produced no draft.", "$"));
            return new ValidationResult(false, issues);
        }

        checkShape(draft, issues);
        checkEvidence(draft, caseFile, playbook, issues);
        checkCitations(draft, caseFile, issues);
        checkText(draft, redactor, issues);
        checkOffer(draft, caseFile, issues);

        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private void checkShape(DraftProposal d, List<ValidationResult.Issue> issues) {
        if (d.confidence() == null || d.confidence().compareTo(BigDecimal.ZERO) < 0
                || d.confidence().compareTo(BigDecimal.ONE) > 0) {
            issues.add(new ValidationResult.Issue(CONFIDENCE_OUT_OF_RANGE,
                    "Confidence must be between 0 and 1.", "$.confidence"));
        }
        if (d.rationale() != null && d.rationale().length() > MAX_RATIONALE) {
            issues.add(new ValidationResult.Issue(RATIONALE_TOO_LONG,
                    "The rationale is " + d.rationale().length() + " characters; the limit is "
                            + MAX_RATIONALE + ".", "$.rationale"));
        }
        if (d.notes() != null && d.notes().length() > MAX_NOTES) {
            issues.add(new ValidationResult.Issue(NOTES_TOO_LONG,
                    "The note to PayPal is " + d.notes().length() + " characters; the limit is "
                            + MAX_NOTES + ".", "$.notes"));
        }
        boolean needsNotes = d.recommendation() == Recommendation.FIGHT
                || d.recommendation() == Recommendation.OFFER;
        if (needsNotes && (d.notes() == null || d.notes().isBlank())) {
            issues.add(new ValidationResult.Issue(NOTES_MISSING,
                    "A " + d.recommendation() + " response needs a note for PayPal.", "$.notes"));
        }
        if (d.recommendation() == Recommendation.FIGHT && (d.evidence() == null || d.evidence().isEmpty())) {
            issues.add(new ValidationResult.Issue(FIGHT_WITHOUT_EVIDENCE,
                    "FIGHT needs at least one evidence entry; without evidence PayPal has nothing to weigh.",
                    "$.evidence"));
        }
    }

    private void checkEvidence(DraftProposal d, CaseFile caseFile, Playbook playbook,
                               List<ValidationResult.Issue> issues) {
        if (d.evidence() == null) {
            return;
        }
        List<String> requested = caseFile.dispute().requestedEvidenceTypes();
        Set<String> knownTracking = caseFile.knownTrackingNumbers();
        Set<String> knownRefunds = caseFile.knownRefundIds();

        for (int i = 0; i < d.evidence().size(); i++) {
            EvidenceItem e = d.evidence().get(i);
            String path = "$.evidence[" + i + "]";
            String type = e.evidenceType();

            if (type == null || type.isBlank()) {
                issues.add(new ValidationResult.Issue(EVIDENCE_TYPE_NOT_ALLOWED,
                        "Every evidence entry needs an evidence_type.", path + ".evidence_type"));
                continue;
            }
            if (!playbook.allowedEvidenceTypes().contains(type)
                    && !(requested != null && requested.contains(type))) {
                issues.add(new ValidationResult.Issue(EVIDENCE_TYPE_NOT_ALLOWED,
                        "Evidence type " + type + " is not allowed for " + playbook.reason()
                                + "; allowed: " + playbook.allowedEvidenceTypes() + ".",
                        path + ".evidence_type"));
            } else if (requested != null && !requested.isEmpty() && !requested.contains(type)) {
                issues.add(new ValidationResult.Issue(EVIDENCE_TYPE_NOT_REQUESTED,
                        "PayPal asked this dispute for " + requested + ", not " + type + ".",
                        path + ".evidence_type"));
            }

            checkRequiredFields(e, type, path, issues);

            if (e.evidenceInfo() != null && e.evidenceInfo().trackingInfo() != null) {
                for (EvidenceItem.TrackingInfo t : e.evidenceInfo().trackingInfo()) {
                    if (t.trackingNumber() != null && !knownTracking.contains(t.trackingNumber())) {
                        issues.add(new ValidationResult.Issue(UNKNOWN_TRACKING_NUMBER,
                                "Tracking number " + t.trackingNumber() + " is not in the merchant's records "
                                        + (knownTracking.isEmpty() ? "(there are no shipments)."
                                        : "(known: " + knownTracking + ")."),
                                path + ".evidence_info.tracking_info"));
                    }
                }
            }
            if (e.evidenceInfo() != null && e.evidenceInfo().refundIds() != null) {
                for (EvidenceItem.RefundId r : e.evidenceInfo().refundIds()) {
                    if (r.refundId() != null && !knownRefunds.contains(r.refundId())) {
                        issues.add(new ValidationResult.Issue(UNKNOWN_REFUND_ID,
                                "Refund ID " + r.refundId() + " is not in the merchant's records "
                                        + (knownRefunds.isEmpty() ? "(no refund was issued)."
                                        : "(known: " + knownRefunds + ")."),
                                path + ".evidence_info.refund_ids"));
                    }
                }
            }
        }
    }

    private void checkRequiredFields(EvidenceItem e, String type, String path,
                                     List<ValidationResult.Issue> issues) {
        boolean ok = switch (type) {
            case Playbook.PROOF_OF_FULFILLMENT -> e.evidenceInfo() != null
                    && e.evidenceInfo().trackingInfo() != null
                    && e.evidenceInfo().trackingInfo().stream().anyMatch(t ->
                    notBlank(t.carrierName()) && notBlank(t.trackingNumber()));
            case Playbook.PROOF_OF_REFUND -> e.evidenceInfo() != null
                    && e.evidenceInfo().refundIds() != null
                    && e.evidenceInfo().refundIds().stream().anyMatch(r -> notBlank(r.refundId()));
            default -> notBlank(e.notes());
        };
        if (!ok) {
            issues.add(new ValidationResult.Issue(EVIDENCE_MISSING_REQUIRED_FIELD,
                    type + " needs " + String.join(" and ", Playbook.requiredFields(type)) + ".", path));
        }
    }

    private void checkCitations(DraftProposal d, CaseFile caseFile, List<ValidationResult.Issue> issues) {
        Set<String> known = caseFile.citationIds();
        List<Citation> citations = d.citations() == null ? List.of() : d.citations();
        boolean argues = d.recommendation() == Recommendation.FIGHT || d.recommendation() == Recommendation.OFFER;
        if (argues && citations.isEmpty()) {
            issues.add(new ValidationResult.Issue(NO_CITATIONS,
                    "A " + d.recommendation() + " response must cite the records it relies on.", "$.citations"));
        }
        for (int i = 0; i < citations.size(); i++) {
            String id = citations.get(i).id();
            if (id == null || !known.contains(id)) {
                issues.add(new ValidationResult.Issue(UNKNOWN_CITATION,
                        "Citation " + id + " is not a record in this case file.", "$.citations[" + i + "].id"));
            }
        }
    }

    private void checkText(DraftProposal d, PiiRedactor redactor, List<ValidationResult.Issue> issues) {
        checkOneText(d.notes(), "$.notes", redactor, issues);
        checkOneText(d.rationale(), "$.rationale", redactor, issues);
        if (d.evidence() != null) {
            for (int i = 0; i < d.evidence().size(); i++) {
                checkOneText(d.evidence().get(i).notes(), "$.evidence[" + i + "].notes", redactor, issues);
            }
        }
    }

    private void checkOneText(String text, String path, PiiRedactor redactor,
                              List<ValidationResult.Issue> issues) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (PiiRedactor.containsRedactionToken(text)) {
            issues.add(new ValidationResult.Issue(REDACTION_TOKEN_IN_TEXT,
                    "This text still contains a redaction token such as " + PiiRedactor.EMAIL_TOKEN
                            + "; rewrite it without it.", path));
        }
        if (redactor != null && redactor.containsPii(text)) {
            issues.add(new ValidationResult.Issue(PII_IN_TEXT,
                    "This text contains buyer personal data (email, phone or street address). "
                            + "City and region are enough.", path));
        }
    }

    private void checkOffer(DraftProposal d, CaseFile caseFile, List<ValidationResult.Issue> issues) {
        if (d.recommendation() != Recommendation.OFFER) {
            return;
        }
        BigDecimal offer = d.offerAmount();
        if (offer == null || offer.compareTo(BigDecimal.ZERO) <= 0) {
            issues.add(new ValidationResult.Issue(OFFER_WITHOUT_AMOUNT,
                    "An OFFER needs an offer_amount above zero.", "$.offer_amount"));
            return;
        }
        BigDecimal disputed = caseFile.dispute().amount() == null ? null : caseFile.dispute().amount().value();
        if (disputed != null && offer.compareTo(disputed) > 0) {
            issues.add(new ValidationResult.Issue(OFFER_EXCEEDS_DISPUTED_AMOUNT,
                    "The offer of " + offer + " exceeds the disputed amount of " + disputed + ".",
                    "$.offer_amount"));
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
