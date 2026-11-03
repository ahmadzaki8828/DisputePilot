import { describe, expect, it } from "vitest";
import { dueInfo } from "./due";

const now = new Date("2026-11-03T12:00:00Z");
const at = (ms: number) => new Date(now.getTime() + ms).toISOString();
const H = 3600_000;

describe("dueInfo", () => {
  it("is red under 48 hours", () => {
    expect(dueInfo(at(47 * H), now)).toEqual({ label: "due in 1d 23h", tone: "urgent" });
  });
  it("is amber between 48 and 96 hours", () => {
    expect(dueInfo(at(72 * H), now).tone).toBe("soon");
  });
  it("is neutral beyond 96 hours and shows days", () => {
    expect(dueInfo(at(9 * 24 * H), now)).toEqual({ label: "due in 9d", tone: "ok" });
  });
  it("shows hours and minutes in the final day", () => {
    expect(dueInfo(at(5 * H + 30 * 60_000), now).label).toBe("due in 5h 30m");
  });
  it("marks past deadlines overdue", () => {
    expect(dueInfo(at(-2 * H), now)).toEqual({ label: "overdue", tone: "urgent" });
  });
  it("handles a missing deadline", () => {
    expect(dueInfo(null, now)).toEqual({ label: "no deadline", tone: "none" });
  });
});
