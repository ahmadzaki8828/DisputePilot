"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { bff } from "@/lib/client";
import { errMessage } from "./ui";

/** The fictional store's seeded orders, offered as suggestions when linking a real sandbox transaction. */
export const SEEDED_ORDERS = ["ORD-1042", "ORD-1043", "ORD-1044", "ORD-1045", "ORD-1046", "ORD-1047", "ORD-1048"];

/**
 * Shown when the merchant's records have no order for the disputed transaction (typical for a real sandbox dispute,
 * whose transaction the demo store never saw). Links the transaction to a seeded order so the agent has records to
 * cite. Needs DP_DEV_ENDPOINTS=true on the backend outside mock mode; the backend says so if it is off.
 */
export function LinkOrder({ transactionId }: { transactionId: string | null | undefined }) {
  const router = useRouter();
  const [orderId, setOrderId] = useState(SEEDED_ORDERS[0]);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  if (!transactionId) return null;

  async function link() {
    setBusy(true);
    setMsg(null);
    try {
      await bff("POST", "/api/dev/link-transaction", { sellerTransactionId: transactionId, orderId });
      setMsg({ kind: "ok", text: `Linked ${transactionId} to ${orderId}. Regenerate the draft to use it.` });
      router.refresh();
    } catch (e) {
      setMsg({ kind: "error", text: errMessage(e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-2 rounded border border-dashed border-black/25 p-3 text-sm dark:border-white/30">
      <p>No order in your records matches transaction <span className="font-mono">{transactionId}</span>. For a sandbox dispute you can borrow one of the demo store&apos;s orders:</p>
      <div className="mt-2 flex flex-wrap items-center gap-2">
        <label htmlFor="link-order" className="sr-only">Demo order</label>
        <select id="link-order" value={orderId} onChange={(e) => setOrderId(e.target.value)} className="rounded border border-black/20 bg-transparent px-2 py-1.5 dark:border-white/25">
          {SEEDED_ORDERS.map((o) => <option key={o} value={o} className="text-black">{o}</option>)}
        </select>
        <button type="button" disabled={busy} onClick={link} className="rounded bg-indigo-600 px-3 py-1.5 text-white disabled:opacity-50">
          {busy ? "Linking…" : "Link to demo order"}
        </button>
      </div>
      {msg && <p role={msg.kind === "error" ? "alert" : "status"} className={`mt-2 ${msg.kind === "error" ? "text-red-600" : "text-emerald-700 dark:text-emerald-300"}`}>{msg.text}</p>}
    </div>
  );
}
