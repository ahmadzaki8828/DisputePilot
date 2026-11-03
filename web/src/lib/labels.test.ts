import { describe, expect, it } from "vitest";
import { aiModeLabel, paypalModeLabel } from "./labels";

describe("aiModeLabel", () => {
  it("names the model for live mode", () => {
    expect(aiModeLabel("live", "claude-haiku-5-5")).toBe("AI: claude-haiku-5-5 (live)");
  });
  it("labels recorded responses honestly", () => {
    expect(aiModeLabel("recorded", "claude-haiku-5-5")).toBe("AI: Recorded responses");
  });
  it("labels hand-written samples honestly", () => {
    expect(aiModeLabel("sample", "")).toBe("AI: Sample responses (no API key)");
  });
  it("falls back to a neutral live label when the model is unknown", () => {
    expect(aiModeLabel("live", null)).toBe("AI: model unknown (live)");
  });
});

describe("paypalModeLabel", () => {
  it("maps mock to Simulated and sandbox to Sandbox", () => {
    expect(paypalModeLabel("mock")).toBe("PayPal: Simulated");
    expect(paypalModeLabel("sandbox")).toBe("PayPal: Sandbox");
  });
});
