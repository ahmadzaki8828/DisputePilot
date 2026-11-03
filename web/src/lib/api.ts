import type { components } from "./api-types";

export type DisputeSummary = components["schemas"]["DisputeSummary"];
export type DisputeDetail = components["schemas"]["DisputeDetail"];
export type Draft = components["schemas"]["Draft"];
export type SystemInfo = components["schemas"]["SystemInfo"];

/**
 * Server-side only: the admin token must never reach the browser.
 * Session C builds the full data layer (DP_API_MODE=mock serves web/mocks/*.json).
 */
export async function apiFetch<T>(path: string): Promise<T> {
  const base = process.env.DP_API_BASE_URL ?? "http://localhost:8080";
  const res = await fetch(`${base}${path}`, {
    headers: { "X-Admin-Token": process.env.DP_ADMIN_TOKEN ?? "" },
    cache: "no-store",
  });
  if (!res.ok) {
    throw new Error(`API ${path} failed with ${res.status}`);
  }
  return res.json() as Promise<T>;
}
