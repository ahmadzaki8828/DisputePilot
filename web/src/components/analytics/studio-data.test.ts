import { describe, expect, it } from "vitest";
import { buildStudioData, hasData, studioRows } from "./studio-data";
import { countdownLabel, orderByDeadline, urgency } from "./DeadlineWidget";
import { analyticsReport } from "./initial-report";
import type { AnalyticsDataset } from "./types";

const dataset: AnalyticsDataset = {
  asOf: "2026-11-05T00:00:00Z",
  disputes: [
    { id: "d1", paypalDisputeId: "DEMO-D-1", simulated: true, reason: "UNAUTHORISED", reasonLabel: "Unauthorised transaction", status: "WAITING_FOR_SELLER_RESPONSE", stage: "CHARGEBACK", amount: 50, currency: "CAD", createdAt: "2026-11-01T10:00:00Z", dueAt: "2026-11-06T00:00:00Z", hoursToDue: 24, dueBucket: "Under 48h", open: true, moneyAtRisk: 50, outcome: null, outcomeLabel: "Pending", won: 0, decided: 0 },
    { id: "d2", paypalDisputeId: "PP-D-2", simulated: false, reason: "OTHER", reasonLabel: "Other", status: "RESOLVED", stage: null, amount: 20, currency: "USD", createdAt: "2026-10-20T10:00:00Z", dueAt: null, hoursToDue: null, dueBucket: "Closed", open: false, moneyAtRisk: 0, outcome: "RESOLVED_SELLER_FAVOUR", outcomeLabel: "Won", won: 1, decided: 1 },
  ],
  drafts: [
    { id: "r1", disputeId: "d1", version: 1, status: "DRAFT", recommendation: "ACCEPT", confidence: 0.8, llmMode: "recorded", provider: "Recorded: Groq", model: "m", latencyMs: null, inputTokens: null, outputTokens: null, grounded: true, editedByHuman: false, latest: true, createdAt: "2026-11-01T10:00:05Z", timeToDraftSeconds: 5 },
    { id: "r2", disputeId: "d2", version: 2, status: "SUBMITTED", recommendation: "FIGHT", confidence: 0.9, llmMode: "live", provider: "Groq", model: "m", latencyMs: 900, inputTokens: 10, outputTokens: 5, grounded: false, editedByHuman: true, latest: true, createdAt: "2026-10-20T11:00:00Z", timeToDraftSeconds: 3600 },
  ],
  submissions: [{ id: "s1", draftId: "r2", disputeId: "d2", action: "PROVIDE_EVIDENCE", status: "SUCCEEDED", createdAt: "2026-10-21T00:00:00Z" }],
  agent: { enabled: false, provider: "", model: "", turnsPerHour: 0, reason: "off" },
};

describe("analytics data", () => {
  it("adds counters and honest AI-source labels to the rows", () => {
    const rows = studioRows(dataset);
    expect(rows.disputes.map((d) => [d.openCount, d.urgentCount, d.kind, d.dueDate])).toEqual([
      [1, 1, "Simulated", "2026-11-06"],
      [0, 0, "PayPal sandbox", null],
    ]);
    expect(rows.drafts.map((d) => [d.aiSource, d.firstDraftSeconds, d.groundedCount])).toEqual([
      ["Recorded AI response", 5, 1],
      ["Groq (live)", null, 0],
    ]);
    expect(rows.submissions[0].succeededCount).toBe(1);
    expect(hasData(dataset)).toBe(true);
  });

  it("defines every field the built-in report uses", () => {
    const def = buildStudioData(dataset);
    const known = new Set<string>(["calendar::week", "winRate", "groundedRate"]);
    for (const s of def.sources) for (const f of (s as { fields: { id: string }[] }).fields) known.add(`${s.id}.${f.id}`);
    const used = JSON.stringify(analyticsReport).match(/"id":"[a-zA-Z]+(\.|::)[a-zA-Z]+"|"id":"(winRate|groundedRate)"/g) ?? [];
    const ids = used.map((u) => u.slice(6, -1));
    expect(ids.length).toBeGreaterThan(20);
    expect(ids.filter((id) => !known.has(id))).toEqual([]);
  });
});

describe("deadline radar", () => {
  it("orders soonest first with no-deadline items last, and labels urgency like the inbox", () => {
    expect(orderByDeadline([{ hours: 100 }, { hours: null }, { hours: -2 }, { hours: 10 }]).map((r) => r.hours)).toEqual([-2, 10, 100, null]);
    expect([countdownLabel(-3), countdownLabel(20), countdownLabel(100), countdownLabel(null)]).toEqual(["Overdue 3h", "20h left", "4d left", "No deadline"]);
    expect([urgency(-1), urgency(47), urgency(90), urgency(200), urgency(null)]).toEqual(["overdue", "urgent", "soon", "ok", "ok"]);
  });
});
