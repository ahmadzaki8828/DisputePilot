// Shape of GET /api/analytics/dataset (backend api/analytics/AnalyticsController.java).

export type AnalyticsDisputeRow = {
  id: string;
  paypalDisputeId: string;
  simulated: boolean;
  reason: string;
  reasonLabel: string;
  status: string;
  stage: string | null;
  amount: number;
  currency: string;
  createdAt: string;
  dueAt: string | null;
  hoursToDue: number | null;
  dueBucket: string;
  open: boolean;
  moneyAtRisk: number;
  outcome: string | null;
  outcomeLabel: "Won" | "Lost" | "Pending";
  won: number;
  decided: number;
};

export type AnalyticsDraftRow = {
  id: string;
  disputeId: string;
  version: number;
  status: string;
  recommendation: string;
  confidence: number;
  llmMode: string;
  provider: string | null;
  model: string | null;
  latencyMs: number | null;
  inputTokens: number | null;
  outputTokens: number | null;
  grounded: boolean;
  editedByHuman: boolean;
  latest: boolean;
  createdAt: string;
  timeToDraftSeconds: number | null;
};

export type AnalyticsSubmissionRow = {
  id: string;
  draftId: string;
  disputeId: string;
  action: string;
  status: string;
  createdAt: string;
};

export type AgentInfo = {
  enabled: boolean;
  provider: string;
  model: string;
  turnsPerHour: number;
  reason: string | null;
};

export type AnalyticsDataset = {
  asOf: string;
  disputes: AnalyticsDisputeRow[];
  drafts: AnalyticsDraftRow[];
  submissions: AnalyticsSubmissionRow[];
  agent: AgentInfo;
};
