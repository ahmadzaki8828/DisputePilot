import type { LlmMode, PayPalMode } from "./types";

/** Single source of the AI label: used by the header badge and on every draft. */
export function aiModeLabel(mode: LlmMode, model: string | null | undefined): string {
  switch (mode) {
    case "live":
      return `AI: ${model || "model unknown"} (live)`;
    case "recorded":
      return "AI: Recorded responses";
    case "sample":
      return "AI: Sample responses (no API key)";
    default:
      return "AI: mode unknown";
  }
}

export function paypalModeLabel(mode: PayPalMode): string {
  return mode === "sandbox" ? "PayPal: Sandbox" : "PayPal: Simulated";
}
