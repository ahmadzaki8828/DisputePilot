import { Suspense } from "react";
import { connection } from "next/server";
import { ErrorPanel, Skeleton } from "@/components/ui";
import { safe } from "@/lib/safe";
import { getMetrics } from "@/lib/server-api";

async function MetricsContent() {
  await connection();
  const r = await safe(getMetrics);
  if (!r.ok) return <ErrorPanel title="Could not load metrics" message={r.error} retryHref="/metrics" />;
  const m = r.data;
    const pct = (v: number | null | undefined) => (v == null ? "—" : `${Math.round(v * 100)}%`);
    const tiles: Array<[string, string]> = [
      ["Open disputes", String(m.openDisputes ?? 0)],
      ["Due within 48h", String(m.dueWithin48h ?? 0)],
      ["Drafts ready", String(m.draftsReady ?? 0)],
      ["Submitted", String(m.submitted ?? 0)],
      ["Mean time to draft", m.meanTimeToDraftSeconds == null ? "—" : `${m.meanTimeToDraftSeconds.toFixed(1)} s`],
      ["Win rate", pct(m.winRate)],
    ];
    return (
      <dl className="grid grid-cols-2 gap-4 md:grid-cols-3">
        {tiles.map(([k, v]) => (
          <div key={k} className="rounded-lg border border-black/15 p-4 dark:border-white/20">
            <dt className="text-sm opacity-70">{k}</dt>
            <dd className="mt-1 text-3xl font-semibold">{v}</dd>
          </div>
        ))}
      </dl>
    );
}

export default function MetricsPage() {
  return (
    <>
      <h1 className="mb-4 text-2xl font-semibold">Metrics</h1>
      <Suspense fallback={<Skeleton label="Loading metrics" />}><MetricsContent /></Suspense>
    </>
  );
}
