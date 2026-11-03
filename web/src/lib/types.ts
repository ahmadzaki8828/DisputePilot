import type { components } from "./api-types";

type S = components["schemas"];

export type SystemInfo = S["SystemInfo"];
export type LlmMode = SystemInfo["llmMode"];
export type PayPalMode = SystemInfo["paypalMode"];
export type Draft = S["Draft"];
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
export type Recommendation = S["Recommendation"];
