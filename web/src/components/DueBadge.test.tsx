import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DueBadge } from "./DueBadge";

const now = new Date("2026-11-03T12:00:00Z");

describe("DueBadge", () => {
  it("is red when under 48 hours", () => {
    render(<DueBadge dueAt="2026-11-04T12:00:00Z" now={now} />);
    const el = screen.getByText("due in 1d 0h");
    expect(el).toHaveAttribute("data-tone", "urgent");
    expect(el.className).toMatch(/red/);
  });
  it("is not red with plenty of time", () => {
    render(<DueBadge dueAt="2026-11-12T12:00:00Z" now={now} />);
    const el = screen.getByText("due in 9d");
    expect(el).toHaveAttribute("data-tone", "ok");
    expect(el.className).not.toMatch(/red/);
  });
});
