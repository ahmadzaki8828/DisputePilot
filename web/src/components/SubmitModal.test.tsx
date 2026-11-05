import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { SubmitModal } from "./SubmitModal";

const evidence = [{ evidence_type: "PROOF_OF_FULFILLMENT", notes: "Delivered." }];

describe("SubmitModal", () => {
  it("shows the exact payload and an idempotency key", () => {
    render(<SubmitModal draftId="d1" action="PROVIDE_EVIDENCE" evidence={evidence} notes="Delivered." onCancel={() => {}} onConfirm={async () => {}} />);
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.getByTestId("payload")).toHaveTextContent('"evidence_type": "PROOF_OF_FULFILLMENT"');
    expect(screen.getByTestId("idempotency-key").textContent).toMatch(/^[0-9a-f-]{36}$/);
  });
  it("reuses the same key when the user retries after a failure", async () => {
    const keys: string[] = [];
    const onConfirm = vi.fn(async (key: string) => {
      keys.push(key);
      if (keys.length === 1) throw new Error("network");
    });
    render(<SubmitModal draftId="d1" action="PROVIDE_EVIDENCE" evidence={evidence} notes="n" onCancel={() => {}} onConfirm={onConfirm} />);
    const shown = screen.getByTestId("idempotency-key").textContent;
    await userEvent.click(screen.getByRole("button", { name: /confirm and submit/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/network/);
    await userEvent.click(screen.getByRole("button", { name: /retry/i }));
    expect(keys).toEqual([shown, shown]);
  });
  it("keeps the key stable across re-opens for the same draft", () => {
    const { unmount } = render(<SubmitModal draftId="d9" action="ACCEPT_CLAIM" evidence={[]} notes="" onCancel={() => {}} onConfirm={async () => {}} />);
    const first = screen.getByTestId("idempotency-key").textContent;
    unmount();
    render(<SubmitModal draftId="d9" action="ACCEPT_CLAIM" evidence={[]} notes="" onCancel={() => {}} onConfirm={async () => {}} />);
    expect(screen.getByTestId("idempotency-key").textContent).toBe(first);
  });
  it("shows the make-offer body for an offer", () => {
    render(<SubmitModal draftId="d7" action="MAKE_OFFER" evidence={[]} notes="Partial refund." offer={{ amount: { currency: "CAD", value: 40 }, offerType: "REFUND" }} onCancel={() => {}} onConfirm={async () => {}} />);
    expect(screen.getByRole("heading")).toHaveTextContent(/make this offer/i);
    const payload = screen.getByTestId("payload").textContent ?? "";
    expect(JSON.parse(payload)).toEqual({ note: "Partial refund.", offer_amount: { currency_code: "CAD", value: "40.00" }, offer_type: "REFUND" });
  });
});
