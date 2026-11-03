import { describe, expect, it } from "vitest";
import { citationIdsInText, isHighlighted } from "./citations";

describe("citations", () => {
  it("extracts cited ids from text", () => {
    expect(citationIdsInText("Signed for [shipment:SHP-77] per [order:ORD-1042].")).toEqual([
      "shipment:SHP-77",
      "order:ORD-1042",
    ]);
  });
  it("highlights a source only when it is the active citation", () => {
    expect(isHighlighted("shipment:SHP-77", "shipment:SHP-77")).toBe(true);
    expect(isHighlighted("order:ORD-1042", "shipment:SHP-77")).toBe(false);
    expect(isHighlighted("order:ORD-1042", null)).toBe(false);
  });
});
