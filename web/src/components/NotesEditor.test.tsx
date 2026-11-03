import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { NotesEditor, NOTES_MAX } from "./NotesEditor";

describe("NotesEditor", () => {
  it("shows a live character counter", async () => {
    render(<NotesEditor value="abc" onChange={() => {}} />);
    expect(screen.getByText(`3 / ${NOTES_MAX}`)).toBeInTheDocument();
  });
  it("flags and blocks text over the limit", () => {
    render(<NotesEditor value={"x".repeat(NOTES_MAX + 1)} onChange={() => {}} />);
    expect(screen.getByText(`${NOTES_MAX + 1} / ${NOTES_MAX}`)).toHaveAttribute("data-over", "true");
    expect(screen.getByRole("alert")).toHaveTextContent(/too long/i);
  });
  it("caps the textarea at the limit", () => {
    render(<NotesEditor value="" onChange={() => {}} />);
    expect(screen.getByRole("textbox")).toHaveAttribute("maxlength", String(NOTES_MAX));
  });
  it("reports edits", async () => {
    const seen: string[] = [];
    render(<NotesEditor value="" onChange={(v) => seen.push(v)} />);
    await userEvent.type(screen.getByRole("textbox"), "a");
    expect(seen).toEqual(["a"]);
  });
});
