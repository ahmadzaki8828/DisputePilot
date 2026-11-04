import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DraftingNotice, POLL_EVERY_MS, POLL_FOR_MS, awaitingAutoDraft } from "./DraftingNotice";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

describe("awaitingAutoDraft", () => {
  it("is true only while PayPal lets the seller act", () => {
    expect(awaitingAutoDraft("WAITING_FOR_SELLER_RESPONSE", ["PROVIDE_EVIDENCE", "SEND_MESSAGE"])).toBe(true);
    expect(awaitingAutoDraft("WAITING_FOR_SELLER_RESPONSE", ["ACCEPT_CLAIM"])).toBe(true);
    expect(awaitingAutoDraft("UNDER_REVIEW", ["SEND_MESSAGE"])).toBe(false);
    expect(awaitingAutoDraft("RESOLVED", [])).toBe(false);
  });
});

describe("DraftingNotice", () => {
  beforeEach(() => { vi.useFakeTimers(); refresh.mockClear(); });
  afterEach(() => vi.useRealTimers());

  it("shows a drafting state and refreshes the page until the draft arrives", () => {
    render(<DraftingNotice busy={false} onGenerate={() => {}} />);
    expect(screen.getByRole("status")).toHaveTextContent(/Drafting a response/);
    expect(screen.getByRole("status")).toHaveTextContent(/30 seconds/);
    act(() => { vi.advanceTimersByTime(POLL_EVERY_MS * 3); });
    expect(refresh).toHaveBeenCalledTimes(3);
  });

  it("gives up after a while and says why a draft may be missing", () => {
    render(<DraftingNotice busy={false} onGenerate={() => {}} />);
    act(() => { vi.advanceTimersByTime(POLL_FOR_MS + POLL_EVERY_MS); });
    expect(screen.getByRole("status")).toHaveTextContent(/rate-limited/);
    const calls = refresh.mock.calls.length;
    act(() => { vi.advanceTimersByTime(POLL_EVERY_MS * 5); });
    expect(refresh.mock.calls.length).toBe(calls);
  });

  it("lets the merchant generate now, and shows drafting while it runs", () => {
    const onGenerate = vi.fn();
    const { rerender } = render(<DraftingNotice busy={false} onGenerate={onGenerate} />);
    screen.getByRole("button", { name: /generate draft now/i }).click();
    expect(onGenerate).toHaveBeenCalled();
    rerender(<DraftingNotice busy onGenerate={onGenerate} />);
    expect(screen.getByRole("button", { name: /drafting/i })).toBeDisabled();
  });
});
