import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Placeholder } from "./Placeholder";

describe("Placeholder", () => {
  it("renders the page title", () => {
    render(<Placeholder title="Inbox" />);
    expect(screen.getByRole("heading", { name: "Inbox" })).toBeInTheDocument();
  });
});
