// @vitest-environment node
import { beforeAll, describe, expect, it } from "vitest";
import { GET, POST } from "./[...path]/route";

const call = (handler: typeof GET, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
  handler(new Request(`http://localhost/bff${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body), headers }), {
    params: Promise.resolve({ path: path.split("?")[0].split("/").filter(Boolean) }),
  });

describe("/bff allowlist (mock API)", () => {
  beforeAll(() => { process.env.DP_API_MODE = "mock"; });

  it("serves the read-only routes the UI polls", async () => {
    for (const p of ["/api/system", "/api/metrics", "/api/evals/latest"]) {
      const res = await call(GET, "GET", p);
      expect(res.status, p).toBe(200);
    }
    const system = await (await call(GET, "GET", "/api/system")).json();
    expect(["live", "recorded", "sample"]).toContain(system.llmMode);
  });

  it("answers 404 for anything that is not on the list", async () => {
    for (const [m, p] of [["GET", "/api/dev/seed"], ["POST", "/api/dev/seed"], ["GET", "/api/disputes"], ["POST", "/webhooks/paypal"], ["POST", "/api/sync"]] as const) {
      const res = await call(m === "GET" ? GET : POST, m, p);
      expect(res.status, `${m} ${p}`).toBe(404);
    }
  });

  it("does not let a GET reach a mutation route", async () => {
    expect((await call(GET, "GET", "/api/demo/reset")).status).toBe(404);
  });

  it("rejects a malformed JSON body", async () => {
    const res = await POST(new Request("http://localhost/bff/api/demo/disputes", { method: "POST", body: "{nope" }), { params: Promise.resolve({ path: ["api", "demo", "disputes"] }) });
    expect(res.status).toBe(400);
  });
});
