package com.disputepilot.paypal;

import com.disputepilot.domain.EvidenceItem;
import java.util.List;

/** Body of provide-evidence: the evidences[] JSON part plus optional file parts. */
public record EvidenceSubmission(List<EvidenceItem> evidences, List<FilePart> files) {

    public record FilePart(String filename, String contentType, byte[] content) {
    }

    public EvidenceSubmission(List<EvidenceItem> evidences) {
        this(evidences, List.of());
    }
}
