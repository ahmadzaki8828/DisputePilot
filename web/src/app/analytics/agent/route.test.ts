import { afterEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";

const turn = JSON.stringify({ messages: [{ role: "user", content: "hi" }] });

describe("/analytics/agent", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it("forwards the turn to the backend with the admin token and the visitor's address", async () => {
    vi.stubEnv("DP_API_MODE", "");
    vi.stubEnv("DP_API_BASE_URL", "http://backend:8080");
    vi.stubEnv("DP_ADMIN_TOKEN", "server-secret");
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"choices":[]}', { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const res = await POST(new Request("http://web/analytics/agent", { method: "POST", body: turn, headers: { "x-forwarded-for": "203.0.113.5" } }));

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ choices: [] });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://backend:8080/api/analytics/agent");
    expect(init.headers["X-Admin-Token"]).toBe("server-secret");
    expect(init.headers["X-Forwarded-For"]).toBe("203.0.113.5");
    expect(init.body).toBe(turn);
  });

  it("passes the backend's rate-limit answer through", async () => {
    vi.stubEnv("DP_API_MODE", "");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response('{"status":429,"detail":"Try later"}', { status: 429 })));
    const res = await POST(new Request("http://web/analytics/agent", { method: "POST", body: turn }));
    expect(res.status).toBe(429);
    expect((await res.json()).detail).toBe("Try later");
  });

  it("is unavailable on the web app's mock data", async () => {
    vi.stubEnv("DP_API_MODE", "mock");
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    const res = await POST(new Request("http://web/analytics/agent", { method: "POST", body: turn }));
    expect(res.status).toBe(503);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
