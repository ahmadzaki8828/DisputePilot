// The built-in "Dispute analytics" report, as AG Studio report state. Field ids ("disputes.moneyAtRisk") refer to
// the data sources in ./studio-data.ts. Pages: Overview, AI drafts, Submissions.
import type { AgPageState, AgReportState, AgWidgetLayoutState } from "ag-studio";
import { DEADLINE_WIDGET, type AnalyticsRegistry } from "./DeadlineWidget";

// Position on the canvas grid, in tracks: 24 columns wide.
const at = (xTrack: number, yTrack: number, xSpan: number, ySpan: number): AgWidgetLayoutState => ({ xTrack, yTrack, xSpan, ySpan });
const title = (text: string) => ({ title: { enabled: true, text } });
const sum = (id: string) => ({ id, aggregation: "sum" as const });
const avg = (id: string) => ({ id, aggregation: "avg" as const });
const countd = (id: string) => ({ id, aggregation: "countd" as const });

const kpi = (text: string, value: { id: string; aggregation?: "sum" | "avg" | "countd" }) => ({
  type: "value" as const,
  dataMapping: { value: [value] },
  format: { ...title(text), caption: { enabled: false, text: "" } },
});

const overview: AgPageState<AnalyticsRegistry> = {
  id: "overview",
  widgets: {
    "kpi-at-risk": kpi("Money at risk", sum("disputes.moneyAtRisk")),
    "kpi-open": kpi("Open disputes", sum("disputes.openCount")),
    "kpi-urgent": kpi("Due within 48h", sum("disputes.urgentCount")),
    "kpi-win-rate": kpi("Win rate (decided)", { id: "winRate" }),
    "reason-per-week": {
      type: "column-chart-stacked",
      dataMapping: {
        categoryKey: [{ id: "calendar::week" }],
        valueKey: [countd("disputes.id")],
        legendKey: [{ id: "disputes.reasonLabel" }],
      },
      format: { ...title("Disputes by reason per week opened"), style: { theme: { common: { legend: { enabled: true } } } } },
    },
    "reason-donut": {
      type: "donut-chart",
      dataMapping: { categoryKey: [{ id: "disputes.reasonLabel" }], valueKey: [sum("disputes.amount")] },
      format: { ...title("Disputed amount by reason"), style: { custom: { pieDataLabel: { enabled: true }, totalLabel: { enabled: true } } } },
    },
    "deadline-radar": {
      type: DEADLINE_WIDGET,
      dataMapping: {
        label: [{ id: "disputes.paypalDisputeId" }],
        hours: [{ id: "disputes.hoursToDue", aggregation: "min" }],
        amount: [sum("disputes.moneyAtRisk")],
      },
      format: title("Deadline radar: open disputes, soonest first"),
    },
    "at-risk-by-due": {
      type: "column-chart-stacked",
      dataMapping: {
        categoryKey: [{ id: "disputes.dueDate" }],
        valueKey: [sum("disputes.moneyAtRisk")],
        legendKey: [{ id: "disputes.reasonLabel" }],
      },
      format: { ...title("Money at risk by response due date"), style: { theme: { common: { legend: { enabled: true } } } } },
    },
    "open-disputes": {
      type: "grid",
      dataMapping: {
        cols: [
          { id: "disputes.paypalDisputeId" },
          { id: "disputes.reasonLabel" },
          { id: "disputes.kind" },
          { id: "disputes.status" },
          { id: "disputes.dueBucket" },
          { id: "disputes.dueAt" },
          { id: "disputes.currency" },
          sum("disputes.amount"),
          sum("disputes.moneyAtRisk"),
        ],
      },
      sort: [{ field: { id: "disputes.dueAt" }, direction: "asc" }],
      format: title("Disputes, soonest deadline first"),
    },
  },
  widgetLayout: {
    "kpi-at-risk": at(0, 0, 6, 7),
    "kpi-open": at(6, 0, 6, 7),
    "kpi-urgent": at(12, 0, 6, 7),
    "kpi-win-rate": at(18, 0, 6, 7),
    "reason-per-week": at(0, 7, 15, 18),
    "reason-donut": at(15, 7, 9, 18),
    "deadline-radar": at(0, 25, 10, 20),
    "at-risk-by-due": at(10, 25, 14, 20),
    "open-disputes": at(0, 45, 24, 20),
  },
};

const ai: AgPageState<AnalyticsRegistry> = {
  id: "ai",
  widgets: {
    "kpi-ttd": kpi("Mean time to first draft (s)", avg("drafts.firstDraftSeconds")),
    "kpi-grounded": kpi("Grounded drafts", { id: "groundedRate" }),
    "kpi-confidence": kpi("Average confidence", avg("drafts.confidence")),
    "kpi-drafts": kpi("Drafts written", countd("drafts.id")),
    "rec-vs-outcome": {
      type: "pivot-grid",
      dataMapping: {
        rows: [{ id: "drafts.recommendation" }],
        columns: [{ id: "disputes.outcomeLabel" }],
        values: [countd("drafts.id")],
      },
      format: {
        ...title("AI recommendation vs outcome (latest drafts)"),
        style: { totalColumns: true, totalColumnsPlacement: "after", grandTotalRow: { enabled: true } },
      },
    },
    "rec-by-reason": {
      type: "bar-chart-stacked",
      dataMapping: {
        categoryKey: [{ id: "disputes.reasonLabel" }],
        valueKey: [countd("drafts.id")],
        legendKey: [{ id: "drafts.recommendation" }],
      },
      format: { ...title("Recommendation by dispute reason"), style: { theme: { common: { legend: { enabled: true } } } } },
    },
    "source-donut": {
      type: "donut-chart",
      dataMapping: { categoryKey: [{ id: "drafts.aiSource" }], valueKey: [countd("drafts.id")] },
      format: { ...title("Where drafts came from"), style: { custom: { pieDataLabel: { enabled: true }, totalLabel: { enabled: true } } } },
    },
    drafts: {
      type: "grid",
      dataMapping: {
        cols: [
          { id: "disputes.paypalDisputeId" },
          { id: "disputes.reasonLabel" },
          { id: "drafts.version" },
          { id: "drafts.recommendation" },
          { id: "drafts.status" },
          { id: "drafts.aiSource" },
          { id: "drafts.model" },
          { id: "drafts.grounded" },
          avg("drafts.confidence"),
          avg("drafts.timeToDraftSeconds"),
        ],
      },
      format: title("Latest drafts"),
    },
  },
  widgetLayout: {
    "kpi-ttd": at(0, 0, 6, 7),
    "kpi-grounded": at(6, 0, 6, 7),
    "kpi-confidence": at(12, 0, 6, 7),
    "kpi-drafts": at(18, 0, 6, 7),
    "rec-vs-outcome": at(0, 7, 12, 18),
    "rec-by-reason": at(12, 7, 12, 18),
    "source-donut": at(0, 25, 8, 20),
    drafts: at(8, 25, 16, 20),
  },
  filter: {
    page: [{ field: { id: "drafts.latest" }, model: { operator: "isTrue" } }],
  },
};

const submissions: AgPageState<AnalyticsRegistry> = {
  id: "submissions",
  widgets: {
    "kpi-submitted": kpi("Submitted to PayPal", sum("submissions.succeededCount")),
    "kpi-attempts": kpi("Submission attempts", countd("submissions.id")),
    "by-action": {
      type: "donut-chart",
      dataMapping: { categoryKey: [{ id: "submissions.action" }], valueKey: [countd("submissions.id")] },
      format: { ...title("Submissions by action"), style: { custom: { pieDataLabel: { enabled: true }, totalLabel: { enabled: true } } } },
    },
    log: {
      type: "grid",
      dataMapping: {
        cols: [
          { id: "submissions.createdAt" },
          { id: "disputes.paypalDisputeId" },
          { id: "submissions.action" },
          { id: "submissions.status" },
          { id: "drafts.recommendation" },
          sum("disputes.amount"),
        ],
      },
      sort: [{ field: { id: "submissions.createdAt" }, direction: "desc" }],
      format: title("Submission log"),
    },
  },
  widgetLayout: {
    "kpi-submitted": at(0, 0, 12, 7),
    "kpi-attempts": at(12, 0, 12, 7),
    "by-action": at(0, 7, 9, 20),
    log: at(9, 7, 15, 20),
  },
};

export const PAGE_TITLES: Record<string, string> = { overview: "Overview", ai: "AI drafts", submissions: "Submissions" };

export const analyticsReport: AgReportState<AnalyticsRegistry> = {
  selectedPageId: overview.id,
  pages: [overview, ai, submissions],
};
