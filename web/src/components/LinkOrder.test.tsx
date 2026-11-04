import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LinkOrder } from "./LinkOrder";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

afterEach(() => vi.unstubAllGlobals());

describe("LinkOrder", () => {
  it("renders nothing without a transaction id", () => {
    const { container } = render(<LinkOrder transactionId={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("links the transaction to the chosen demo order through the /bff proxy", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ orderId: "ORD-1044" }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);
    render(<LinkOrder transactionId="SANDBOX-TXN-9" />);
    await userEvent.selectOptions(screen.getByLabelText("Demo order"), "ORD-1044");
    await userEvent.click(screen.getByRole("button", { name: /link to demo order/i }));

    expect(await screen.findByRole("status")).toHaveTextContent("Linked SANDBOX-TXN-9 to ORD-1044");
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/bff/api/dev/link-transaction");
    expect(JSON.parse(String(init.body))).toEqual({ sellerTransactionId: "SANDBOX-TXN-9", orderId: "ORD-1044" });
    expect(refresh).toHaveBeenCalled();
  });

  it("shows the backend's reason when linking is switched off", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ detail: "Linking a transaction needs DP_DEV_ENDPOINTS=true" }), { status: 403 })));
    render(<LinkOrder transactionId="SANDBOX-TXN-9" />);
    await userEvent.click(screen.getByRole("button", { name: /link to demo order/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("DP_DEV_ENDPOINTS=true");
  });
});
