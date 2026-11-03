package com.disputepilot.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** One entry of PayPal's evidences[] in the shape we would submit. */
public record EvidenceItem(
        @JsonProperty("evidence_type") String evidenceType,
        @JsonProperty("evidence_info") EvidenceInfo evidenceInfo,
        String notes) {

    public record EvidenceInfo(
            @JsonProperty("tracking_info") List<TrackingInfo> trackingInfo,
            @JsonProperty("refund_ids") List<RefundId> refundIds) {
    }

    public record TrackingInfo(
            @JsonProperty("carrier_name") String carrierName,
            @JsonProperty("carrier_name_other") String carrierNameOther,
            @JsonProperty("tracking_number") String trackingNumber) {
    }

    public record RefundId(@JsonProperty("refund_id") String refundId) {
    }
}
