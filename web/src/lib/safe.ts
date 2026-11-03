import { ApiError } from "./server-api";

export type Loaded<T> = { ok: true; data: T } | { ok: false; error: string; status: number };

/** Lets pages branch on failure without building JSX inside try/catch. */
export async function safe<T>(fn: () => Promise<T>): Promise<Loaded<T>> {
  try {
    return { ok: true, data: await fn() };
  } catch (e) {
    if (e instanceof ApiError) return { ok: false, error: e.message, status: e.status };
    return { ok: false, error: e instanceof Error ? e.message : "Unexpected error", status: 500 };
  }
}
