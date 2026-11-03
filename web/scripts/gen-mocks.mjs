// Derives web/mocks/*.json from ../fixtures so the mock UI matches the backend's seed data.
// Drafts are hand-written samples (llmMode "sample"): they are NOT model output.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const rd = (p) => JSON.parse(fs.readFileSync(path.join(root, p), "utf8"));
const out = (n, v) => fs.writeFileSync(path.join(root, "web/mocks", n), JSON.stringify(v, null, 2) + "\n");

const orders = rd("fixtures/merchant/orders.json").orders;
const shipments = rd("fixtures/merchant/shipments.json").shipments;
const messages = rd("fixtures/merchant/messages.json").messages;
const policy = rd("fixtures/merchant/policy.json");
delete policy.note;

const labels = {
  MERCHANDISE_OR_SERVICE_NOT_RECEIVED: "Item not received",
  MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED: "Not as described",
  UNAUTHORISED: "Unauthorised transaction",
  CREDIT_NOT_PROCESSED: "Credit not processed",
  DUPLICATE_TRANSACTION: "Duplicate transaction",
  INCORRECT_AMOUNT: "Incorrect amount",
};
const actionOf = { provide_evidence: "PROVIDE_EVIDENCE", accept_claim: "ACCEPT_CLAIM", make_offer: "MAKE_OFFER", send_message: "SEND_MESSAGE" };
const uuid = (kind, n) => `${kind}0000000-0000-4000-8000-00000000${String(n).padStart(4, "0")}`;
const BASE = Date.parse("2026-11-03T12:00:00Z");

const files = fs.readdirSync(path.join(root, "fixtures/paypal/disputes")).sort();
const details = files.map((f, i) => {
  const d = rd(`fixtures/paypal/disputes/${f}`);
  const txn = d.disputed_transactions[0].seller_transaction_id;
  const order = orders.find((o) => o.paypalTransactionId === txn) ?? null;
  const ship = order ? shipments.filter((s) => s.orderId === order.id) : [];
  const msgs = order ? messages.filter((m) => m.orderId === order.id) : [];
  return {
    id: uuid("1", i + 1),
    paypalDisputeId: d.dispute_id,
    simulated: true,
    reason: d.reason,
    reasonLabel: labels[d.reason] ?? d.reason,
    status: d.status,
    stage: d.dispute_life_cycle_stage ?? null,
    amount: { currency: d.dispute_amount.currency_code, value: Number(d.dispute_amount.value) },
    // Shift the fixture's deadline so mock data always looks current: due = now + offset.
    _dueInHours: Math.round((Date.parse(d.seller_response_due_date) - BASE) / 3600_000),
    requestedEvidence: d.evidences.filter((e) => e.source === "REQUESTED_FROM_SELLER").map((e) => e.evidence_type),
    allowedActions: d.links.map((l) => actionOf[l.rel]).filter(Boolean),
    messages: d.messages.map((m) => ({ postedBy: m.posted_by, postedAt: m.time_posted, content: m.content })),
    caseFile: { order, shipments: ship, customerMessages: msgs, policy },
  };
});

const byDispute = (id) => details.find((d) => d.paypalDisputeId === id);
const cite = (id, label) => ({ id, kind: id.split(":")[0], label });
const ok = { passed: true, issues: [] };
function draft(n, id, p) {
  const d = byDispute(id);
  return {
    id: uuid("2", n), disputeId: d.id, version: 1, status: "DRAFT", missingInfo: [],
    validation: ok, llmMode: "sample", model: null, promptVersion: null,
    inputTokens: null, outputTokens: null, latencyMs: null, editedByHuman: false,
    createdAt: "2026-11-03T11:00:00Z", ...p,
  };
}
const refundOf = (orderId) => orders.find((o) => o.id === orderId).refunds[0]?.id ?? "REF-0000";

const drafts = [
  draft(1, "DEMO-D-1001", {
    recommendation: "FIGHT", confidence: 0.93,
    rationale: "The parcel was delivered on Oct 21 and signed for by the buyer, and the buyer's own message acknowledges the tracking status.",
    evidence: [{ evidence_type: "PROOF_OF_FULFILLMENT", evidence_info: { tracking_info: [{ carrier_name: "OTHER", carrier_name_other: "Northline Courier", tracking_number: "NLC4417002931" }] }, notes: "Delivered Oct 21 to Toronto, signed for by J. Ellis." }],
    notes: "Order ORD-1042 shipped with Northline Courier (tracking NLC4417002931) and was delivered on 2026-10-21 in Toronto, signed for by J. Ellis. Our policy requires a signature on orders over CAD 150.",
    citations: [cite("dispute:DEMO-D-1001", "Dispute DEMO-D-1001"), cite("order:ORD-1042", "Order ORD-1042"), cite("shipment:SHP-77", "Shipment SHP-77"), cite("policy:shipping", "Shipping policy")],
  }),
  draft(2, "DEMO-D-1002", {
    recommendation: "OFFER", confidence: 0.6, status: "NEEDS_REVIEW",
    rationale: "There is no shipment record for this order, so proof of delivery cannot be shown. A partial-refund offer limits the loss.",
    evidence: [{ evidence_type: "OTHER", notes: "We cannot locate a tracking record and would like to offer a partial refund." }],
    notes: "We could not find tracking for order ORD-1043 and would like to resolve this with a partial refund.",
    citations: [cite("dispute:DEMO-D-1002", "Dispute DEMO-D-1002"), cite("order:ORD-1043", "Order ORD-1043")],
    missingInfo: ["Tracking number or proof of shipment"],
    validation: { passed: false, issues: [{ code: "MISSING_EVIDENCE", message: "No proof of fulfilment is available for this order.", path: "evidence[0]" }] },
  }),
  draft(3, "DEMO-D-1003", {
    recommendation: "FIGHT", confidence: 0.72,
    rationale: "The listing disclosed that denim is garment-washed and colour varies slightly; the item matches its description.",
    evidence: [{ evidence_type: "OTHER", notes: "Product description discloses natural colour variation." }],
    notes: "Our product descriptions state that denim and linen items are garment-washed and that colour varies slightly between pieces and photos.",
    citations: [cite("dispute:DEMO-D-1003", "Dispute DEMO-D-1003"), cite("order:ORD-1044", "Order ORD-1044"), cite("msg:MSG-4", "Buyer message MSG-4"), cite("policy:productDescriptions", "Product description policy")],
  }),
  draft(4, "DEMO-D-1004", {
    recommendation: "ACCEPT", confidence: 0.81,
    rationale: "Delivery was not signed for and there is no evidence linking the buyer to the purchase. Fighting would likely lose and cost the seller time.",
    evidence: [],
    notes: "",
    citations: [cite("dispute:DEMO-D-1004", "Dispute DEMO-D-1004"), cite("order:ORD-1045", "Order ORD-1045"), cite("shipment:SHP-79", "Shipment SHP-79")],
  }),
  draft(5, "DEMO-D-1005", {
    recommendation: "FIGHT", confidence: 0.9,
    rationale: "A refund for this order was already issued, which answers the credit-not-processed claim.",
    evidence: [{ evidence_type: "PROOF_OF_REFUND", evidence_info: { refund_ids: [{ refund_id: refundOf("ORD-1046") }] }, notes: "Refund already issued." }],
    notes: `Refund ${refundOf("ORD-1046")} for order ORD-1046 was issued to the original payment method.`,
    citations: [cite("dispute:DEMO-D-1005", "Dispute DEMO-D-1005"), cite("order:ORD-1046", "Order ORD-1046"), cite("policy:refunds", "Refund policy")],
  }),
  draft(6, "DEMO-D-1006", {
    recommendation: "FIGHT", confidence: 0.7,
    rationale: "One of the two charges was already refunded.",
    evidence: [{ evidence_type: "PROOF_OF_REFUND", evidence_info: { refund_ids: [{ refund_id: refundOf("ORD-1048") }] }, notes: "Duplicate charge refunded." }],
    notes: `The duplicate charge on order ORD-1048 was refunded (${refundOf("ORD-1048")}).`,
    citations: [cite("dispute:DEMO-D-1006", "Dispute DEMO-D-1006"), cite("order:ORD-1048", "Order ORD-1048")],
  }),
];

for (const d of details) {
  const dr = drafts.find((x) => x.disputeId === d.id);
  d.latestDraft = { recommendation: dr.recommendation, confidence: dr.confidence, status: dr.status };
}

out("disputes.json", details);
out("drafts.json", drafts);
out("system.json", { paypalMode: "mock", llmMode: "sample", model: "", simulatedEnabled: true });
out("metrics.json", { openDisputes: 6, dueWithin48h: 0, draftsReady: 6, submitted: 0, meanTimeToDraftSeconds: null, winRate: null });
out("evals.json", { mode: "fake", generatedAt: "2026-11-03T00:00:00Z", model: null, promptVersion: "mock", cases: 0, recommendationAccuracy: 0, evidenceTypeAccuracy: 0, groundingPassRate: 0, injectionResistance: 0, meanInputTokens: 0, meanOutputTokens: 0, meanLatencyMs: 0, estimatedCostPerDraftUsd: 0, results: [] });
out("audit.json", details.map((d, i) => ({ id: i + 1, at: "2026-11-03T11:00:00Z", actor: "system", action: "DISPUTE_SYNCED", disputeId: d.id, details: {} })));
console.log("mocks written:", details.length, "disputes");
