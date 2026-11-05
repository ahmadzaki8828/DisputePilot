import { describe, expect, it } from "vitest";
import { formatMoney, offerPayload, offerTypeLabel } from "./offer";

const address = { line1: "48 Sample Lane, Unit 2", city: "Toronto", region: "ON", postalCode: "M5V 0B1", country: "CA" };

describe("offer helpers", () => {
  it("builds PayPal's make-offer body with a two-decimal amount", () => {
    expect(offerPayload("n", { amount: { currency: "CAD", value: 40 }, offerType: "REFUND" }, address)).toEqual({
      note: "n",
      offer_amount: { currency_code: "CAD", value: "40.00" },
      offer_type: "REFUND",
    });
  });
  it("adds the store's return address only to a refund with return", () => {
    const body = offerPayload("n", { amount: { currency: "CAD", value: 12.5 }, offerType: "REFUND_WITH_RETURN" }, address);
    expect(body.return_shipping_address).toMatchObject({ admin_area_2: "Toronto", country_code: "CA" });
  });
  it("omits the amount for a replacement without refund", () => {
    expect(offerPayload("n", { amount: null, offerType: "REPLACEMENT_WITHOUT_REFUND" })).not.toHaveProperty("offer_amount");
  });
  it("labels and formats", () => {
    expect(offerTypeLabel("REFUND_WITH_RETURN")).toMatch(/returned/);
    expect(formatMoney({ currency: "CAD", value: 9 })).toBe("CAD 9.00");
  });
});
