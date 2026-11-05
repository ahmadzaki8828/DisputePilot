import type { components } from "./api-types";

type S = components["schemas"];
export type Offer = S["Offer"];
export type Address = S["Address"];

const LABELS: Record<Offer["offerType"], string> = {
  REFUND: "Refund (buyer keeps the item)",
  REFUND_WITH_RETURN: "Refund once the item is returned",
  REFUND_WITH_REPLACEMENT: "Partial refund plus a replacement",
  REPLACEMENT_WITHOUT_REFUND: "Replacement only, no refund",
};

export function offerTypeLabel(type: Offer["offerType"]): string {
  return LABELS[type] ?? type;
}

export function formatMoney(m: { currency: string; value: number } | null | undefined): string {
  return m ? `${m.currency} ${Number(m.value).toFixed(2)}` : "—";
}

/** The make-offer body PayPal receives, built the same way as the backend's stored request payload. */
export function offerPayload(note: string, offer: Offer, returnAddress?: Address | null) {
  const body: Record<string, unknown> = { note };
  if (offer.amount) body.offer_amount = { currency_code: offer.amount.currency, value: Number(offer.amount.value).toFixed(2) };
  body.offer_type = offer.offerType;
  if (offer.offerType === "REFUND_WITH_RETURN" && returnAddress) {
    body.return_shipping_address = {
      address_line_1: returnAddress.line1,
      admin_area_2: returnAddress.city,
      admin_area_1: returnAddress.region,
      postal_code: returnAddress.postalCode,
      country_code: returnAddress.country,
    };
  }
  return body;
}
