// AG Studio data sources for /analytics: disputes, drafts and submissions from GET /api/analytics/dataset, the
// relationships between them, a calendar on when disputes were opened, and a few measures. The descriptions are
// also what the dashboard agent reads to understand the data, so they say plainly what each field means.
import type { AgDataSourcesDefinition, AgExpressionFieldDefinition, AgFieldDefinition, AgFormat, AgRelationDefinition } from "ag-studio";
import type { AnalyticsRegistry } from "./DeadlineWidget";
import type { AnalyticsDataset } from "./types";

const CALENDAR_ID = "calendar";

type FieldSpec = [id: string, name: string, format: AgFormat, hide?: boolean, description?: string];
const fields = (specs: FieldSpec[]): AgFieldDefinition[] =>
  specs.map(([id, name, format, hide, description]) => ({ id, name, format, ...(hide && { hide }), ...(description && { description }) }));

const manyToOne = (id: string, source: string, target: string): AgRelationDefinition => {
  const [sourceTable, sourceField] = source.split(".");
  const [targetTable, targetField] = target.split(".");
  return { id, source: { tableId: sourceTable, fieldId: sourceField }, target: { tableId: targetTable, fieldId: targetField }, type: "many-to-one" };
};

const ratio = (id: string, name: string, description: string, numerator: string, denominator: string): AgExpressionFieldDefinition => ({
  id,
  name,
  description,
  format: "percentageFormat",
  isMeasure: true,
  expression: { operator: "divide", inputs: [{ id: numerator, aggregation: "sum" }, { id: denominator, aggregation: "sum" }] },
});

/** Rows as Studio reads them: the API's rows plus a few 0/1 counters and day-only dates for charting. */
export function studioRows(data: AnalyticsDataset) {
  const disputes = data.disputes.map((d) => ({
    ...d,
    dueDate: d.dueAt?.slice(0, 10) ?? null,
    openCount: d.open ? 1 : 0,
    urgentCount: d.open && d.hoursToDue != null && d.hoursToDue < 48 ? 1 : 0,
    kind: d.simulated ? "Simulated" : "PayPal sandbox",
  }));
  const drafts = data.drafts.map((d) => ({
    ...d,
    groundedCount: d.grounded ? 1 : 0,
    one: 1,
    firstDraftSeconds: d.version === 1 ? d.timeToDraftSeconds : null,
    aiSource: d.llmMode === "live" ? `${d.provider ?? "Live"} (live)` : d.llmMode === "recorded" ? "Recorded AI response" : "Hand-written sample",
  }));
  const submissions = data.submissions.map((s) => ({ ...s, succeededCount: s.status === "SUCCEEDED" ? 1 : 0 }));
  return { disputes, drafts, submissions };
}

export const hasData = (data: AnalyticsDataset) => data.disputes.length > 0;

export function buildStudioData(data: AnalyticsDataset): AgDataSourcesDefinition<AnalyticsRegistry> {
  const rows = studioRows(data);
  const dates = rows.disputes.map((d) => d.createdAt.slice(0, 10)).sort();
  const today = data.asOf.slice(0, 10);
  return {
    description:
      'PayPal disputes for a small online store, "Maple & Thread Co.", as handled by DisputePilot: an AI copilot drafts each response ' +
      "(FIGHT with evidence, ACCEPT the claim, or OFFER a partial refund) and a human approves every submission. " +
      "Disputes are real PayPal sandbox disputes or simulated ones (IDs starting DEMO-). Amounts are in each dispute's own currency " +
      "and are not converted. Buyer-written text is not part of this data.",
    sources: [
      {
        id: "disputes",
        description: "One row per dispute. Money at risk is the disputed amount while the dispute is still open.",
        data: rows.disputes,
        fields: fields([
          ["id", "Dispute row ID", "textFormat", true],
          ["paypalDisputeId", "Dispute", "textFormat"],
          ["reasonLabel", "Reason", "textFormat", false, "Why the buyer opened the dispute, e.g. Item not received."],
          ["reason", "PayPal reason code", "textFormat", true],
          ["status", "Status", "textFormat"],
          ["stage", "Stage", "textFormat", false, "PayPal life-cycle stage: INQUIRY, CHARGEBACK, PRE_ARBITRATION…"],
          ["kind", "Source", "textFormat", false, "Simulated (DEMO-) or PayPal sandbox."],
          ["simulated", "Simulated", "booleanFormat", true],
          ["amount", "Disputed amount", "currencyFormat"],
          ["currency", "Currency", "textFormat"],
          ["createdAt", "Opened", "dateTimeFormat"],
          ["dueAt", "Response due", "dateTimeFormat"],
          ["dueDate", "Due date", "dateFormat"],
          ["hoursToDue", "Hours to deadline", "decimalFormat", false, "Hours until the seller response is due; negative when overdue."],
          ["dueBucket", "Deadline", "textFormat", false, "Overdue, Under 48h, 2-7 days, Over 7 days, No deadline, or Closed."],
          ["open", "Is open", "booleanFormat"],
          ["moneyAtRisk", "Money at risk", "currencyFormat"],
          ["openCount", "Open disputes", "integerFormat", false, "1 for an open dispute; sum it to count open disputes."],
          ["urgentCount", "Due within 48h", "integerFormat", false, "1 for an open dispute due within 48 hours."],
          ["outcome", "PayPal outcome", "textFormat", true],
          ["outcomeLabel", "Outcome", "textFormat", false, "Won (seller's favour), Lost, or Pending."],
          ["won", "Won", "integerFormat", true],
          ["decided", "Decided", "integerFormat", true],
        ]),
      },
      {
        id: "drafts",
        name: "AI drafts",
        description: "Every response the AI drafted. A dispute can have several versions; Latest marks the one in use.",
        data: rows.drafts,
        fields: fields([
          ["id", "Draft ID", "textFormat", true],
          ["disputeId", "Dispute row ID", "textFormat", true],
          ["version", "Version", "integerFormat"],
          ["status", "Draft status", "textFormat", false, "DRAFT, NEEDS_REVIEW, APPROVED, SUBMITTED, REJECTED or SUPERSEDED."],
          ["recommendation", "AI recommendation", "textFormat", false, "FIGHT, ACCEPT, OFFER or NEEDS_INFO."],
          ["confidence", "Confidence", "percentageFormat"],
          ["aiSource", "AI source", "textFormat", false, "Live model, recorded AI response, or hand-written sample."],
          ["llmMode", "AI mode", "textFormat", true],
          ["provider", "Model provider", "textFormat"],
          ["model", "Model", "textFormat"],
          ["latencyMs", "Model latency (ms)", "integerFormat"],
          ["inputTokens", "Input tokens", "integerFormat"],
          ["outputTokens", "Output tokens", "integerFormat"],
          ["grounded", "Grounded", "booleanFormat", false, "The deterministic validator found every claim cited to a source record."],
          ["groundedCount", "Grounded drafts", "integerFormat", true],
          ["one", "Drafts", "integerFormat", true],
          ["editedByHuman", "Edited by a person", "booleanFormat"],
          ["latest", "Latest", "booleanFormat"],
          ["createdAt", "Drafted", "dateTimeFormat"],
          ["timeToDraftSeconds", "Time to draft (s)", "decimalFormat", false, "Seconds from the dispute arriving to this draft."],
          ["firstDraftSeconds", "Time to first draft (s)", "decimalFormat", false, "Time to draft for version 1 only."],
        ]),
      },
      {
        id: "submissions",
        description: "Responses a person approved and submitted to PayPal.",
        data: rows.submissions,
        fields: fields([
          ["id", "Submission ID", "textFormat", true],
          ["draftId", "Draft ID", "textFormat", true],
          ["disputeId", "Dispute row ID", "textFormat", true],
          ["action", "Action", "textFormat", false, "PROVIDE_EVIDENCE, ACCEPT_CLAIM or MAKE_OFFER."],
          ["status", "Submission status", "textFormat"],
          ["succeededCount", "Succeeded", "integerFormat"],
          ["createdAt", "Submitted", "dateTimeFormat"],
        ]),
      },
    ],
    relationships: [
      manyToOne("draft-dispute", "drafts.disputeId", "disputes.id"),
      manyToOne("submission-draft", "submissions.draftId", "drafts.id"),
      { id: "disputes-opened-calendar", source: { tableId: "disputes", fieldId: "createdAt" }, target: { calendarId: CALENDAR_ID }, truncate: "day" },
    ],
    calendars: [
      {
        id: CALENDAR_ID,
        label: "Opened",
        range: { from: { type: "date", value: dates[0] ?? today }, to: { type: "date", value: dates.at(-1) ?? today } },
        fragments: ["year", "quarter", "month", "week", "day", "dayOfWeek"],
      },
    ],
    expressions: [
      ratio("winRate", "Win rate", "Disputes won / disputes PayPal has decided. Blank until PayPal decides one.", "disputes.won", "disputes.decided"),
      ratio("groundedRate", "Grounded rate", "Share of AI drafts that passed the grounding validator.", "drafts.groundedCount", "drafts.one"),
    ],
  };
}
