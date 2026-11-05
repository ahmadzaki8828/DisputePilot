// Server-side only: loads the analytics dataset. Holds the admin token; never import from a "use client" file.
import fs from "node:fs";
import path from "node:path";
import type { AnalyticsDataset, AnalyticsDisputeRow, AnalyticsDraftRow } from "@/components/analytics/types";

export const backendBase = () => {
  const configured = process.env.DP_API_BASE_URL ?? "http://localhost:8080";
  // Render's private-network reference is a bare host:port; add the scheme when it is missing.
  return /^https?:\/\//.test(configured) ? configured : `http://${configured}`;
};

export const isMockApi = () => process.env.DP_API_MODE === "mock";

const MOCK_AGENT_REASON = "The dashboard agent needs the DisputePilot backend with a live model; this page is running on the web app's mock data.";

export function dueBucket(open: boolean, hours: number | null): string {
  if (!open) return "Closed";
  if (hours == null) return "No deadline";
  if (hours < 0) return "Overdue";
  if (hours < 48) return "Under 48h";
  return hours <= 168 ? "2-7 days" : "Over 7 days";
}

type MockDispute = {
  id: string; paypalDisputeId: string; simulated: boolean; reason: string; reasonLabel: string; status: string;
  stage?: string | null; amount: { currency: string; value: number | string }; _dueInHours?: number; createdAt?: string;
  outcome?: string | null;
};
type MockDraft = {
  id: string; disputeId: string; version: number; status: string; recommendation: string; confidence: number;
  llmMode: string; model: string | null; latencyMs: number | null; inputTokens: number | null; outputTokens: number | null;
  validation: { passed: boolean }; editedByHuman: boolean; createdAt: string;
};

/** The same tables, built from web/mocks so the page works with DP_API_MODE=mock. */
export function mockDataset(now = new Date()): AnalyticsDataset {
  const read = <T,>(name: string) => JSON.parse(fs.readFileSync(path.join(process.cwd(), "mocks", name), "utf8")) as T;
  const raw = read<MockDispute[]>("disputes.json");
  const rawDrafts = read<MockDraft[]>("drafts.json");
  const disputes: AnalyticsDisputeRow[] = raw.map((d, i) => {
    const open = d.status !== "RESOLVED";
    const hours = d._dueInHours ?? 240;
    const amount = Number(d.amount.value);
    const outcomeLabel = !d.outcome ? "Pending" : /SELLER_FAVO/i.test(d.outcome) ? "Won" : "Lost";
    return {
      id: d.id, paypalDisputeId: d.paypalDisputeId, simulated: d.simulated, reason: d.reason, reasonLabel: d.reasonLabel,
      status: d.status, stage: d.stage ?? null, amount, currency: d.amount.currency,
      createdAt: d.createdAt ?? new Date(now.getTime() - (i + 1) * 36 * 3600_000).toISOString(),
      dueAt: new Date(now.getTime() + hours * 3600_000).toISOString(), hoursToDue: hours, dueBucket: dueBucket(open, hours),
      open, moneyAtRisk: open ? amount : 0, outcome: d.outcome ?? null, outcomeLabel,
      won: outcomeLabel === "Won" ? 1 : 0, decided: outcomeLabel === "Pending" ? 0 : 1,
    };
  });
  const created = new Map(disputes.map((d) => [d.id, d.createdAt]));
  const drafts: AnalyticsDraftRow[] = rawDrafts.map((d) => {
    const start = created.get(d.disputeId);
    return {
      id: d.id, disputeId: d.disputeId, version: d.version, status: d.status, recommendation: d.recommendation,
      confidence: d.confidence, llmMode: d.llmMode, provider: "Hand-written samples", model: d.model, latencyMs: d.latencyMs,
      inputTokens: d.inputTokens, outputTokens: d.outputTokens, grounded: d.validation.passed, editedByHuman: d.editedByHuman,
      latest: d.status !== "SUPERSEDED", createdAt: d.createdAt,
      timeToDraftSeconds: start ? Math.max(0, (Date.parse(d.createdAt) - Date.parse(start)) / 1000) : null,
    };
  });
  return {
    asOf: now.toISOString(), disputes, drafts, submissions: [],
    agent: { enabled: false, provider: "", model: "", turnsPerHour: 0, reason: MOCK_AGENT_REASON },
  };
}

export async function loadDataset(): Promise<AnalyticsDataset> {
  if (isMockApi()) return mockDataset();
  const res = await fetch(`${backendBase()}/api/analytics/dataset`, {
    cache: "no-store",
    headers: { "X-Admin-Token": process.env.DP_ADMIN_TOKEN ?? "" },
  });
  if (!res.ok) throw new Error(`The analytics dataset could not be loaded (HTTP ${res.status}).`);
  return (await res.json()) as AnalyticsDataset;
}
