package com.disputepilot.agent;

import com.disputepilot.domain.Citation;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Recommendation;
import java.math.BigDecimal;
import java.util.List;

/** The model's {@code submit_draft} output, parsed. Not yet validated and never submitted as-is. */
public record DraftProposal(
        Recommendation recommendation,
        BigDecimal confidence,
        String rationale,
        List<EvidenceItem> evidence,
        String notes,
        List<Citation> citations,
        List<String> missingInfo,
        BigDecimal offerAmount) {

    public DraftProposal withEvidenceAndNotes(List<EvidenceItem> newEvidence, String newNotes) {
        return new DraftProposal(recommendation, confidence, rationale, newEvidence, newNotes, citations,
                missingInfo, offerAmount);
    }
}
