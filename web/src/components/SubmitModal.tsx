"use client";

import { useState } from "react";
import { type Address, type Offer, offerPayload } from "@/lib/offer";
import type { Evidence, SubmitAction } from "@/lib/types";

const keys = new Map<string, string>();

/** One key per draft+action, kept for the page's lifetime so a retry never creates a second submission. */
export function idempotencyKeyFor(draftId: string, action: SubmitAction): string {
  const k = `${draftId}:${action}`;
  let key = keys.get(k);
  if (!key) {
    try {
      key = sessionStorage.getItem(`dp-idem-${k}`) ?? undefined;
    } catch {}
    if (!key) {
      key = crypto.randomUUID();
      try {
        sessionStorage.setItem(`dp-idem-${k}`, key);
      } catch {}
    }
    keys.set(k, key);
  }
  return key;
}

export function SubmitModal(props: {
  draftId: string;
  action: SubmitAction;
  evidence: Evidence[];
  notes: string;
  offer?: Offer | null;
  returnAddress?: Address | null;
  onCancel: () => void;
  onConfirm: (idempotencyKey: string) => Promise<void>;
}) {
  const [key] = useState(() => idempotencyKeyFor(props.draftId, props.action));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const payload =
    props.action === "PROVIDE_EVIDENCE"
      ? { evidences: props.evidence.map((e) => ({ ...e, notes: e.notes ?? props.notes })) }
      : props.action === "MAKE_OFFER" && props.offer
        ? offerPayload(props.notes, props.offer, props.returnAddress)
        : { note: props.notes, accept_claim_reason: "REASON_NOT_SET" };
  const title = props.action === "ACCEPT_CLAIM" ? "Accept the claim?" : props.action === "MAKE_OFFER" ? "Make this offer to the buyer?" : "Submit evidence to PayPal?";

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await props.onConfirm(key);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Submission failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div role="dialog" aria-modal="true" aria-labelledby="submit-title" className="max-h-[90vh] w-full max-w-2xl overflow-auto rounded-lg bg-white p-5 text-neutral-900 shadow-xl dark:bg-neutral-900 dark:text-neutral-100">
        <h2 id="submit-title" className="text-lg font-semibold">
          {title}
        </h2>
        <p className="mt-1 text-sm opacity-80">This sends exactly the payload below to PayPal ({props.action}). It cannot be undone.</p>
        <pre data-testid="payload" className="mt-3 max-h-64 overflow-auto rounded bg-black/5 p-3 text-xs dark:bg-white/10">{JSON.stringify(payload, null, 2)}</pre>
        <p className="mt-3 text-sm">Idempotency-Key: <code data-testid="idempotency-key" className="text-xs">{key}</code></p>
        {error && <p role="alert" className="mt-2 text-sm text-red-600">{error}</p>}
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={props.onCancel} className="rounded border px-3 py-1.5 text-sm">Cancel</button>
          <button type="button" disabled={busy} onClick={confirm} className="rounded bg-indigo-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
            {error ? "Retry" : "Confirm and submit"}
          </button>
        </div>
      </div>
    </div>
  );
}
