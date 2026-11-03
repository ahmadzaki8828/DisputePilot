import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ModeBadges } from "./ModeBadges";

describe("ModeBadges", () => {
  it("shows live AI with the model name", () => {
    render(<ModeBadges info={{ paypalMode: "sandbox", llmMode: "live", model: "openai/gpt-oss-120b", simulatedEnabled: true }} />);
    expect(screen.getByText("PayPal: Sandbox")).toBeInTheDocument();
    expect(screen.getByText("AI: openai/gpt-oss-120b (live)")).toBeInTheDocument();
  });
  it("shows recorded responses", () => {
    render(<ModeBadges info={{ paypalMode: "mock", llmMode: "recorded", model: "x", simulatedEnabled: true }} />);
    expect(screen.getByText("PayPal: Simulated")).toBeInTheDocument();
    expect(screen.getByText("AI: Recorded responses")).toBeInTheDocument();
  });
  it("shows sample responses", () => {
    render(<ModeBadges info={{ paypalMode: "mock", llmMode: "sample", model: "", simulatedEnabled: true }} />);
    expect(screen.getByText("AI: Sample responses (no API key)")).toBeInTheDocument();
  });
  it("says so when system info is unavailable", () => {
    render(<ModeBadges info={null} />);
    expect(screen.getByText("Modes unavailable")).toBeInTheDocument();
  });
});
