package com.disputepilot.evals;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.agent.DraftProposal;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.Playbook;
import com.disputepilot.domain.Money;
import com.disputepilot.domain.Offer;
import com.disputepilot.domain.Recommendation;
import com.disputepilot.domain.ValidationResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Held-out case 14: the order was already refunded, so accepting or offering a refund would pay twice. */
class DoubleRefundGuardTest {

    private static final String CASE = "14-inr-refund-already-issued";

    private static List<String> codes(ValidationResult v) {
        return v.issues().stream().map(ValidationResult.Issue::code).toList();
    }

    @Test
    void case14RecordedAcceptNoLongerPassesValidation() throws Exception {
        var r = EvalReplay.replay(CASE);
        assertThat(r.outcome().draft().recommendation()).isEqualTo(Recommendation.ACCEPT);
        assertThat(r.outcome().validation().passed()).isFalse();
        assertThat(codes(r.outcome().validation())).contains(DraftValidator.REFUND_ALREADY_ISSUED);
        assertThat(r.outcome().validation().issues().toString()).contains("PROOF_OF_REFUND");
    }

    @Test
    void aRefundOfferOnARefundedTransactionFailsToo() throws Exception {
        var r = EvalReplay.replay(CASE);
        DraftProposal offer = new DraftProposal(Recommendation.OFFER, new BigDecimal("0.8"), "Offer a refund.",
                List.of(), "We will refund you.", r.outcome().draft().citations(), List.of(),
                new Offer(new Money("CAD", new BigDecimal("92.00")), Offer.REFUND));
        var v = new DraftValidator().validate(offer, r.assembled().caseFile(),
                Playbook.forReason(r.assembled().caseFile().dispute().reason()), r.assembled().redactor());
        assertThat(codes(v)).contains(DraftValidator.REFUND_ALREADY_ISSUED);
    }

    @Test
    void anUnrefundedTransactionIsUnaffected() throws Exception {
        var r = EvalReplay.replay("04-unauthorised-charge");
        assertThat(codes(r.outcome().validation())).doesNotContain(DraftValidator.REFUND_ALREADY_ISSUED);
    }
}
