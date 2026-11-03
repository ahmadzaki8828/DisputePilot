export function ErrorPanel({ title = "Something went wrong", message, retryHref }: { title?: string; message: string; retryHref?: string }) {
  return (
    <div role="alert" className="rounded-lg border border-red-300 bg-red-50 p-4 text-red-900 dark:border-red-800 dark:bg-red-950 dark:text-red-100">
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-sm">{message}</p>
      {retryHref && <a href={retryHref} className="mt-2 inline-block text-sm underline">Try again</a>}
    </div>
  );
}

export function EmptyState({ title, children }: { title: string; children?: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-dashed border-black/20 p-8 text-center dark:border-white/25">
      <p className="font-medium">{title}</p>
      {children && <div className="mt-2 text-sm opacity-80">{children}</div>}
    </div>
  );
}

export function Skeleton({ label = "Loading" }: { label?: string }) {
  return (
    <div role="status" aria-live="polite" className="animate-pulse space-y-3 p-1">
      <span className="sr-only">{label}…</span>
      <div className="h-6 w-1/3 rounded bg-black/10 dark:bg-white/10" />
      <div className="h-40 rounded bg-black/10 dark:bg-white/10" />
    </div>
  );
}

export function SimulatedTag() {
  return <span title="Simulated dispute: created inside DisputePilot, not by PayPal" className="ml-2 rounded border border-fuchsia-500/60 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-fuchsia-800 dark:text-fuchsia-300">Simulated</span>;
}

export function errMessage(e: unknown): string {
  return e instanceof Error ? e.message : "Unexpected error";
}
