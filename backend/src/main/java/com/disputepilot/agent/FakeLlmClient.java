package com.disputepilot.agent;

import com.disputepilot.domain.LlmMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test double that returns a canned {@code submit_draft} tool call. Unit tests never touch the network.
 * Session B's agent loop and validator tests build on it; the canned draft is valid against the plan's schema.
 */
public class FakeLlmClient implements LlmClient {

    public static final String CANNED_DRAFT = """
            {
              "recommendation": "FIGHT",
              "confidence": 0.93,
              "rationale": "Northline Courier delivered the parcel with a signature before the dispute was opened.",
              "evidence": [{
                "evidence_type": "PROOF_OF_FULFILLMENT",
                "evidence_info": {"tracking_info": [{"carrier_name": "OTHER", "carrier_name_other": "Northline Courier", "tracking_number": "NLC4417002931"}]},
                "notes": "Delivered and signed for by J. Ellis on 2026-10-21."
              }],
              "notes": "The order shipped via Northline Courier and was delivered with a signature on 2026-10-21.",
              "citations": [
                {"id": "order:ORD-1042", "kind": "order", "label": "Order ORD-1042"},
                {"id": "shipment:SHP-77", "kind": "shipment", "label": "Shipment SHP-77, delivered and signed"}
              ],
              "missing_info": []
            }
            """;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger calls = new AtomicInteger();
    private final String draftJson;

    public FakeLlmClient() {
        this(CANNED_DRAFT);
    }

    public FakeLlmClient(String draftJson) {
        this.draftJson = draftJson;
    }

    public int callCount() {
        return calls.get();
    }

    @Override
    public LlmResult run(LlmRequest request) {
        calls.incrementAndGet();
        JsonNode input = mapper.readTree(draftJson);
        return new LlmResult(
                null,
                List.of(new LlmRequest.ToolCall("fake-1", "submit_draft", input)),
                LlmMode.RECORDED,
                "fake-model",
                100,
                50,
                1);
    }
}
