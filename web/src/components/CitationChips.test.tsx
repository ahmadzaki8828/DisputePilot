import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { CitationHighlightProvider, CitationChip, SourceBlock } from "./CitationChips";

function Harness() {
  return (
    <CitationHighlightProvider>
      <CitationChip citation={{ id: "shipment:SHP-77", kind: "shipment", label: "Shipment SHP-77" }} />
      <SourceBlock id="shipment:SHP-77">shipment body</SourceBlock>
      <SourceBlock id="order:ORD-1042">order body</SourceBlock>
    </CitationHighlightProvider>
  );
}

describe("citation highlight", () => {
  it("highlights the source while the chip is hovered and clears on leave", async () => {
    render(<Harness />);
    const chip = screen.getByRole("button", { name: /Shipment SHP-77/ });
    await userEvent.hover(chip);
    expect(screen.getByText("shipment body").closest("[data-source]")).toHaveAttribute("data-highlighted", "true");
    expect(screen.getByText("order body").closest("[data-source]")).toHaveAttribute("data-highlighted", "false");
    await userEvent.unhover(chip);
    expect(screen.getByText("shipment body").closest("[data-source]")).toHaveAttribute("data-highlighted", "false");
  });
  it("keeps the highlight on click and keyboard focus", async () => {
    render(<Harness />);
    await userEvent.tab();
    expect(screen.getByText("shipment body").closest("[data-source]")).toHaveAttribute("data-highlighted", "true");
  });
});
