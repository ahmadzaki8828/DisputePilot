"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";
import { POLL_EVERY_MS, POLL_FOR_MS } from "./DraftingNotice";

/** Refreshes the inbox while some dispute is still waiting for the agent's draft, then stops. */
export function InboxRefresh({ active }: { active: boolean }) {
  const router = useRouter();
  const routerRef = useRef(router);
  useEffect(() => { routerRef.current = router; }, [router]);
  useEffect(() => {
    if (!active) return;
    const started = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - started >= POLL_FOR_MS) clearInterval(timer);
      else routerRef.current.refresh();
    }, POLL_EVERY_MS);
    return () => clearInterval(timer);
  }, [active]);
  return null;
}
