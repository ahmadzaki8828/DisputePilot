import { NextResponse } from "next/server";
import { ApiError, mutate, proxyGet } from "@/lib/server-api";

// The browser only ever talks to this route; the admin token stays on the server. Anything not listed here is a 404.
const ID = "[0-9a-f-]+";
const ALLOWED: Array<[string, RegExp]> = [
  // read-only: modes, KPIs, eval report, audit trail, and the dispute/draft polling used by the "drafting" state
  ["GET", /^\/api\/system$/],
  ["GET", /^\/api\/metrics$/],
  ["GET", /^\/api\/evals\/latest$/],
  ["GET", /^\/api\/audit$/],
  ["GET", new RegExp(`^/api/disputes/${ID}$`)],
  ["GET", new RegExp(`^/api/disputes/${ID}/drafts/latest$`)],
  // demo controls
  ["POST", /^\/api\/demo\/disputes$/],
  ["POST", /^\/api\/demo\/reset$/],
  // drafts and submission
  ["POST", new RegExp(`^/api/disputes/${ID}/drafts$`)],
  ["POST", new RegExp(`^/api/disputes/${ID}/sync$`)],
  ["PATCH", new RegExp(`^/api/drafts/${ID}$`)],
  ["POST", new RegExp(`^/api/drafts/${ID}/approve$`)],
  ["POST", new RegExp(`^/api/drafts/${ID}/submit$`)],
  // link a sandbox transaction to a demo order (the backend refuses unless dev endpoints are on)
  ["POST", /^\/api\/dev\/link-transaction$/],
];

const problem = (status: number, title: string, detail?: string, extra: Record<string, unknown> = {}) =>
  NextResponse.json({ title, detail, status, ...extra }, { status });

async function handle(req: Request, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const target = "/" + path.join("/");
  if (!ALLOWED.some(([m, re]) => m === req.method && re.test(target))) return problem(404, "Not allowed");

  // The backend rate-limits per visitor; it only sees this server, so pass the visitor's address along.
  const forwarded = req.headers.get("x-forwarded-for");
  const headers: Record<string, string> = forwarded ? { "X-Forwarded-For": forwarded } : {};

  try {
    if (req.method === "GET") {
      return NextResponse.json((await proxyGet(target + new URL(req.url).search, headers)) ?? {}, { headers: { "Cache-Control": "no-store" } });
    }
    let body: unknown = undefined;
    try {
      const text = await req.text();
      body = text ? JSON.parse(text) : undefined;
    } catch {
      return problem(400, "Invalid JSON", "Request body is not valid JSON.");
    }
    const idem = req.headers.get("idempotency-key");
    if (idem) headers["Idempotency-Key"] = idem;
    return NextResponse.json((await mutate(req.method, target, body, headers)) ?? {});
  } catch (e) {
    if (e instanceof ApiError) return problem(e.status, "Request failed", e.message, { paypalDebugId: e.debugId });
    return problem(500, "Unexpected error", "Something went wrong.");
  }
}

export { handle as GET, handle as POST, handle as PATCH };
