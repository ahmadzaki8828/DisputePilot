import { NextResponse } from "next/server";
import { ApiError, mutate } from "@/lib/server-api";

// The browser only ever talks to this route; the admin token stays on the server.
const ALLOWED: Array<[string, RegExp]> = [
  ["POST", /^\/api\/demo\/disputes$/],
  ["POST", /^\/api\/demo\/reset$/],
  ["POST", /^\/api\/disputes\/[0-9a-f-]+\/drafts$/],
  ["POST", /^\/api\/disputes\/[0-9a-f-]+\/sync$/],
  ["PATCH", /^\/api\/drafts\/[0-9a-f-]+$/],
  ["POST", /^\/api\/drafts\/[0-9a-f-]+\/approve$/],
  ["POST", /^\/api\/drafts\/[0-9a-f-]+\/submit$/],
  ["POST", /^\/api\/dev\/link-transaction$/],
];

async function handle(req: Request, ctx: { params: Promise<{ path: string[] }> }) {
  const { path } = await ctx.params;
  const target = "/" + path.join("/");
  if (!ALLOWED.some(([m, re]) => m === req.method && re.test(target))) {
    return NextResponse.json({ title: "Not allowed", status: 404 }, { status: 404 });
  }
  let body: unknown = undefined;
  try {
    const text = await req.text();
    body = text ? JSON.parse(text) : undefined;
  } catch {
    return NextResponse.json({ title: "Invalid JSON", detail: "Request body is not valid JSON.", status: 400 }, { status: 400 });
  }
  const idem = req.headers.get("idempotency-key");
  try {
    const result = await mutate(req.method, target, body, idem ? { "idempotency-key": idem, "Idempotency-Key": idem } : {});
    return NextResponse.json(result ?? {});
  } catch (e) {
    if (e instanceof ApiError) {
      return NextResponse.json({ title: "Request failed", detail: e.message, status: e.status, paypalDebugId: e.debugId }, { status: e.status });
    }
    return NextResponse.json({ title: "Unexpected error", detail: "Something went wrong.", status: 500 }, { status: 500 });
  }
}

export { handle as POST, handle as PATCH };
