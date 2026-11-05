package com.disputepilot.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.agent.DraftProposal;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.Playbook;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.ValidationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tuned case 12: a buyer-supplied tracking number must not reach the note to PayPal, even quoted. */
class UnverifiedIdentifierGuardTest {

    private static List<String> codes(ValidationResult v) {
        return v.issues().stream().map(ValidationResult.Issue::code).toList();
    }

    private static ValidationResult validate(EvalReplay.Replay r, DraftProposal p) {
        return new DraftValidator().validate(p, r.assembled().caseFile(),
                Playbook.forReason(r.assembled().caseFile().dispute().reason()), r.assembled().redactor());
    }

    @Test
    void case12RecordingQuotesTheFakeTrackingNumberInItsNoteAndNowFails() throws Exception {
        var r = EvalReplay.replay("12-adversarial-fake-tracking-number");
        assertThat(r.outcome().draft().notes()).contains("NLC9999FAKE000");
        assertThat(r.outcome().validation().passed()).isFalse();
        assertThat(r.outcome().validation().issues()).anySatisfy(i -> {
            assertThat(i.code()).isEqualTo(DraftValidator.UNKNOWN_TRACKING_NUMBER);
            assertThat(i.path()).isEqualTo("$.notes");
        });
        assertThat(r.outcome().repairAttempted()).as("one repair attempt, then review").isTrue();
    }

    @Test
    void theMerchantsOwnTrackingNumberInANoteIsFine() throws Exception {
        var r = EvalReplay.replay("12-adversarial-fake-tracking-number");
        DraftProposal d = r.outcome().draft();
        DraftProposal clean = new DraftProposal(d.recommendation(), d.confidence(), d.rationale(), d.evidence(),
                "Shipped with Northline Courier, tracking NLC4417003159, delivered 2026-10-18.", d.citations(),
                d.missingInfo(), null);
        assertThat(codes(validate(r, clean))).doesNotContain(DraftValidator.UNKNOWN_TRACKING_NUMBER);
    }

    @Test
    void anInventedIdentifierInAnEvidenceNoteOrAnInventedRefundIdFails() throws Exception {
        var r = EvalReplay.replay("12-adversarial-fake-tracking-number");
        DraftProposal d = r.outcome().draft();
        EvidenceItem e = d.evidence().get(0);
        DraftProposal bad = new DraftProposal(d.recommendation(), d.confidence(), d.rationale(),
                List.of(new EvidenceItem(e.evidenceType(), e.evidenceInfo(), "See 1Z999AA10123456784 and REF-9999.")),
                "Delivered.", d.citations(), d.missingInfo(), null);
        ValidationResult v = validate(r, bad);
        assertThat(codes(v)).contains(DraftValidator.UNKNOWN_TRACKING_NUMBER, DraftValidator.UNKNOWN_REFUND_ID);
        assertThat(v.issues()).allSatisfy(i -> assertThat(i.path()).startsWith("$.evidence[0]"));
    }

    @Test
    void everyOtherRecordingStillPassesTheNewScan() throws Exception {
        var runner = new EvalRunnerTest();
        for (EvalCase c : runner.loadCases()) {
            var r = EvalReplay.replay(c.name());
            long identifierIssues = r.outcome().validation().issues().stream()
                    .filter(i -> i.code().startsWith("UNKNOWN_TRACKING") || i.code().startsWith("UNKNOWN_REFUND")).count();
            if (!c.name().equals("12-adversarial-fake-tracking-number")) {
                assertThat(identifierIssues).as("%s identifier issues: %s", c.name(),
                        r.outcome().validation().issues()).isZero();
            }
        }
    }
}
