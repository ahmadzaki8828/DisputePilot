package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.domain.DisputeReason;
import com.disputepilot.domain.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaybookTest {

    @Test
    void itemNotReceivedAllowsFulfilmentProofAndLeansFight() {
        Playbook p = Playbook.forReason("MERCHANDISE_OR_SERVICE_NOT_RECEIVED");
        assertThat(p.allowedEvidenceTypes()).containsExactly("PROOF_OF_FULFILLMENT", "PROOF_OF_REFUND", "OTHER");
        assertThat(p.defaultLeaning()).isEqualTo(Recommendation.FIGHT);
    }

    @Test
    void unauthorisedLeansAccept() {
        assertThat(Playbook.forReason("UNAUTHORISED").defaultLeaning()).isEqualTo(Recommendation.ACCEPT);
    }

    @Test
    void notAsDescribedDoesNotAllowFulfilmentProof() {
        assertThat(Playbook.forReason(DisputeReason.MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED).allowedEvidenceTypes())
                .doesNotContain("PROOF_OF_FULFILLMENT");
    }

    @Test
    void unknownReasonFallsBackToOtherAndNeedsInfo() {
        Playbook p = Playbook.forReason("SOMETHING_NEW");
        assertThat(p.reason()).isEqualTo(DisputeReason.OTHER);
        assertThat(p.defaultLeaning()).isEqualTo(Recommendation.NEEDS_INFO);
    }

    @Test
    void paypalsRequestedTypesNarrowThePlaybook() {
        Playbook p = Playbook.forReason("MERCHANDISE_OR_SERVICE_NOT_RECEIVED");
        assertThat(p.effectiveEvidenceTypes(List.of("PROOF_OF_FULFILLMENT"))).containsExactly("PROOF_OF_FULFILLMENT");
        assertThat(p.effectiveEvidenceTypes(List.of())).isEqualTo(p.allowedEvidenceTypes());
    }

    @Test
    void requestedTypesTheReasonDoesNotAllowAreStillHonoured() {
        // PayPal is the authority on what it will accept; if it asks for something odd, pass it through.
        Playbook p = Playbook.forReason("MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED");
        assertThat(p.effectiveEvidenceTypes(List.of("PROOF_OF_DELIVERY_SIGNATURE")))
                .containsExactly("PROOF_OF_DELIVERY_SIGNATURE");
    }

    @Test
    void eachEvidenceTypeNamesTheFieldsItNeeds() {
        assertThat(Playbook.requiredFields("PROOF_OF_FULFILLMENT")).anyMatch(f -> f.contains("tracking_number"));
        assertThat(Playbook.requiredFields("PROOF_OF_REFUND")).anyMatch(f -> f.contains("refund_id"));
        assertThat(Playbook.requiredFields("OTHER")).containsExactly("notes");
    }
}
