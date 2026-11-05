package com.disputepilot.agent;

import com.disputepilot.domain.Citation;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.domain.Offer;
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
        Offer offer) {

    public DraftProposal withEvidenceAndNotes(List<EvidenceItem> newEvidence, String newNotes) {
        return new DraftProposal(recommendation, confidence, rationale, newEvidence, newNotes, citations,
                missingInfo, offer);
    }

    /** An offer amount written without a currency is in the dispute's currency. */
    public DraftProposal withOfferCurrency(String currency) {
        if (offer == null) {
            return this;
        }
        return new DraftProposal(recommendation, confidence, rationale, evidence, notes, citations, missingInfo,
                offer.withCurrencyIfMissing(currency));
    }
}
