"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

/** How long to keep refreshing while the agent works: a live model can take ~30 s, plus the webhook worker's wait. */
export const POLL_EVERY_MS = 4000;
export const POLL_FOR_MS = 120_000;

/**
 * Shown on a dispute that PayPal says the seller can answer but that has no draft yet. The agent drafts on its own
 * when a dispute arrives, so this refreshes the page until the draft lands (or gives up and says so).
 */
export function DraftingNotice({ onGenerate, busy }: { onGenerate: () => void; busy: boolean }) {
  const router = useRouter();
  const routerRef = useRef(router); // keep the interval independent of the router object's identity
  useEffect(() => { routerRef.current = router; }, [router]);
  const [timedOut, setTimedOut] = useState(false);

  useEffect(() => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - started >= POLL_FOR_MS) {
        setTimedOut(true);
        clearInterval(timer);
        return;
      }
      routerRef.current.refresh();
    }, POLL_EVERY_MS);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="mt-2 text-sm" role="status" aria-live="polite">
      {timedOut ? (
        <p>No draft arrived. The model may be rate-limited right now (the free tier caps tokens per minute). Try again in a minute.</p>
      ) : (
        <p className="flex items-center gap-2">
          <span aria-hidden className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-indigo-600 border-t-transparent" />
          Drafting a response… The agent starts on its own when a dispute arrives; with a live model this can take about 30 seconds. This page updates automatically.
        </p>
      )}
      <button type="button" disabled={busy} className="mt-2 rounded border px-3 py-1.5 disabled:opacity-50" onClick={onGenerate}>
        {busy ? "Drafting…" : "Generate draft now"}
      </button>
    </div>
  );
}

/** True when the agent should be drafting: PayPal lets the seller act and there is no draft yet. */
export function awaitingAutoDraft(status: string, allowedActions: string[]): boolean {
  return status === "WAITING_FOR_SELLER_RESPONSE" && allowedActions.some((a) => a === "PROVIDE_EVIDENCE" || a === "ACCEPT_CLAIM");
}
