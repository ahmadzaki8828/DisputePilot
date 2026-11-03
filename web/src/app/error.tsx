"use client";

import { ErrorPanel } from "@/components/ui";

export default function Error({ error, reset }: { error: Error; reset: () => void }) {
  return (
    <div>
      <ErrorPanel message={error.message || "Unexpected error"} />
      <button type="button" onClick={reset} className="mt-3 rounded border px-3 py-1.5 text-sm">Try again</button>
    </div>
  );
}
