import { NextResponse } from "next/server";
import { backendBase, isMockApi } from "../dataset";

// The dashboard agent's only way out of the browser. AG Studio's adapter posts one Chat Completions turn here; this
// adds the admin token and the visitor's address (the backend rate-limits per visitor) and forwards it. The model
// provider key lives only in the backend, which also fixes the model and caps output.
const MAX_BODY_BYTES = 2_000_000;

const problem = (status: number, title: string, detail: string) => NextResponse.json({ title, detail, status }, { status });

export async function POST(req: Request) {
  if (isMockApi()) {
    return problem(503, "Agent unavailable", "The dashboard agent needs the DisputePilot backend with a live model (DP_API_MODE=mock serves sample data only).");
  }
  const body = await req.text();
  if (new TextEncoder().encode(body).length > MAX_BODY_BYTES) return problem(413, "Too large", "The conversation is too long; start a new one.");

  const forwarded = req.headers.get("x-forwarded-for");
  let upstream: Response;
  try {
    upstream = await fetch(`${backendBase()}/api/analytics/agent`, {
      method: "POST",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        "X-Admin-Token": process.env.DP_ADMIN_TOKEN ?? "",
        ...(forwarded ? { "X-Forwarded-For": forwarded } : {}),
      },
      body,
      signal: req.signal,
    });
  } catch {
    return problem(503, "Agent unavailable", "The DisputePilot API is not reachable.");
  }
  const text = await upstream.text();
  return new Response(text || "{}", {
    status: upstream.status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
