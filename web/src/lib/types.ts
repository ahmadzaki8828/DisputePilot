import type { components } from "./api-types";

type S = components["schemas"];

/** The backend reports `sample` when only hand-written samples exist; the contract enum may lag behind. */
export type LlmMode = "live" | "recorded" | "sample";
export type PayPalMode = "mock" | "sandbox";

export type SystemInfo = Omit<S["SystemInfo"], "llmMode" | "paypalMode"> & {
  llmMode: LlmMode;
  paypalMode: PayPalMode;
};
export type Draft = Omit<S["Draft"], "llmMode"> & { llmMode: LlmMode };
export type DisputeSummary = S["DisputeSummary"];
export type DisputeDetail = S["DisputeDetail"];
export type Citation = S["Citation"];
export type Evidence = S["Evidence"];
export type Metrics = S["Metrics"];
export type EvalReport = S["EvalReport"];
export type AuditEntry = S["AuditEntry"];
export type Submission = S["Submission"];
export type DemoTemplate = S["DemoTemplate"];
export type SubmitAction = Submission["action"];
export type Recommendation = "FIGHT" | "ACCEPT" | "OFFER" | "NEEDS_INFO";
