import type { Metadata } from "next";
import Link from "next/link";
import { Suspense } from "react";
import { connection } from "next/server";
import { AnalyticsStudio } from "@/components/analytics/AnalyticsStudio";
import { hasData } from "@/components/analytics/studio-data";
import { ErrorPanel, Skeleton } from "@/components/ui";
import { safe } from "@/lib/safe";
import { loadDataset } from "./dataset";

export const metadata: Metadata = { title: "Analytics · DisputePilot" };

function Explainer({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="max-w-2xl rounded-lg border border-black/15 p-5 dark:border-white/20">
      <h2 className="mb-2 font-semibold">{title}</h2>
      <div className="space-y-2 text-sm opacity-80">{children}</div>
    </section>
  );
}

async function AnalyticsContent() {
  await connection();
  // AG Studio is commercial: it needs a licence key at runtime, which is never committed to this public repo.
  const licenseKey = process.env.AG_STUDIO_LICENSE_KEY?.trim();
  if (!licenseKey) {
    return (
      <Explainer title="Analytics needs an AG Studio licence key">
        <p>
          This page is an AG Studio dashboard over your disputes, AI drafts and submissions, with an AI assistant that builds and edits it from a
          plain-English question. AG Studio is a commercial product, so it only runs when the web server has a licence key.
        </p>
        <p>
          To turn it on, set <code>AG_STUDIO_LICENSE_KEY</code> in the web server&apos;s environment (a free trial key works) and reload. Everything else in
          DisputePilot works without it; the <Link className="underline" href="/metrics">Metrics</Link> page shows the headline numbers.
        </p>
      </Explainer>
    );
  }
  const r = await safe(loadDataset);
  if (!r.ok) return <ErrorPanel title="Could not load analytics" message={r.error} retryHref="/analytics" />;
  const data = r.data;
  if (!hasData(data)) {
    return (
      <Explainer title="No disputes yet">
        <p>Simulate a dispute from the <Link className="underline" href="/">Inbox</Link>, then come back to see it here.</p>
      </Explainer>
    );
  }
  return (
    <>
      <p className="mb-3 flex flex-wrap items-center gap-2 text-sm">
        <span className="rounded-full border border-black/15 px-2 py-0.5 text-xs dark:border-white/20">
          {data.agent.enabled ? `Dashboard agent: ${data.agent.provider} · ${data.agent.model} (live)` : "Dashboard agent: off"}
        </span>
        <span className="opacity-70">
          {data.agent.enabled
            ? `Open the AI panel in Studio and ask a question; it builds or edits widgets for you. Up to ${data.agent.turnsPerHour} model turns per visitor per hour.`
            : data.agent.reason}
        </span>
      </p>
      <AnalyticsStudio data={data} licenseKey={licenseKey} />
      <p className="mt-2 text-xs opacity-60">
        Data as of {new Date(data.asOf).toUTCString()}. Amounts are in each dispute&apos;s own currency. Your dashboard edits are kept in this browser only.
      </p>
    </>
  );
}

export default function AnalyticsPage() {
  return (
    <>
      <h1 className="mb-1 text-2xl font-semibold">Analytics</h1>
      <p className="mb-4 text-sm opacity-70">Disputes, AI drafts and submissions in an AG Studio dashboard you can edit, or ask the agent to edit for you.</p>
      <Suspense fallback={<Skeleton label="Loading analytics" />}>
        <AnalyticsContent />
      </Suspense>
    </>
  );
}
