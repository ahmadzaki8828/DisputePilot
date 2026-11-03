"use client";

import { useSyncExternalStore } from "react";
import { DemoControls } from "./DemoControls";

const KEY = "dp-tour-dismissed";
const subscribe = (cb: () => void) => {
  window.addEventListener(KEY, cb);
  return () => window.removeEventListener(KEY, cb);
};
const read = () => {
  try {
    return localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
};

export function TourBanner() {
  const dismissed = useSyncExternalStore(subscribe, read, () => false);
  if (dismissed) {
    return <div className="mb-4"><DemoControls /></div>;
  }
  return (
    <section aria-label="Demo tour" className="mb-6 rounded-lg border border-indigo-300 bg-indigo-50 p-4 text-indigo-950 dark:border-indigo-800 dark:bg-indigo-950/50 dark:text-indigo-100">
      <div className="flex items-start justify-between gap-3">
        <p className="text-sm">
          This is a live sandbox demo. Click <strong>Simulate a dispute</strong> to watch a webhook arrive and the agent draft a response. Nothing touches real money.
        </p>
        <button type="button" aria-label="Dismiss tour" className="rounded px-2 text-lg leading-none" onClick={() => { try { localStorage.setItem(KEY, "1"); } catch {} window.dispatchEvent(new Event(KEY)); }}>×</button>
      </div>
      <DemoControls />
    </section>
  );
}
