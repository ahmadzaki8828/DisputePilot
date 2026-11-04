import { Suspense } from "react";
import { connection } from "next/server";
import { EmptyState, ErrorPanel, Skeleton } from "@/components/ui";
import { safe } from "@/lib/safe";
import { getEvalReport } from "@/lib/server-api";

async function EvalsContent() {
  await connection();
  const res = await safe(getEvalReport);
  if (!res.ok) return <ErrorPanel title="Could not load the eval report" message={res.error} retryHref="/evals" />;
  const r = res.data;
  {
    if (!r.cases) return <EmptyState title="No eval report yet">Run <code>./mvnw -B -Peval test</code> in <code>backend/</code> to generate one.</EmptyState>;
    const pct = (v?: number) => (v == null ? "—" : `${Math.round(v * 100)}%`);
    const tiles: Array<[string, string]> = [
      ["Recommendation accuracy", pct(r.recommendationAccuracy)],
      ["Evidence-type accuracy", pct(r.evidenceTypeAccuracy)],
      ["Grounding pass rate", pct(r.groundingPassRate)],
      ["Injection resistance", pct(r.injectionResistance)],
      ["Mean tokens in / out", `${Math.round(r.meanInputTokens ?? 0)} / ${Math.round(r.meanOutputTokens ?? 0)}`],
      ["Est. cost per draft", r.estimatedCostPerDraftUsd == null ? "Not priced" : `$${r.estimatedCostPerDraftUsd.toFixed(4)}`],
    ];
    return (
      <>
        <p className="mb-4 rounded border border-amber-400/60 p-3 text-sm">
          {r.mode === "live" ? "Live run" : "Fake mode: replays recorded responses; not a live measurement"}
          {" · "}{r.cases} cases · prompt {r.promptVersion}{r.provider ? ` · ${r.provider}` : ""}{r.model ? ` · model ${r.model}` : ""} · generated {r.generatedAt ? new Date(r.generatedAt).toLocaleString("en-CA") : "unknown"}
        </p>
        <dl className="grid grid-cols-2 gap-4 md:grid-cols-3">
          {tiles.map(([k, v]) => <div key={k} className="rounded-lg border border-black/15 p-4 dark:border-white/20"><dt className="text-sm opacity-70">{k}</dt><dd className="mt-1 text-2xl font-semibold">{v}</dd></div>)}
        </dl>
        <div className="mt-6 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Per-case eval results</caption>
            <thead><tr className="border-b"><th className="py-2 pr-3">Case</th><th className="pr-3">Expected</th><th className="pr-3">Actual</th><th className="pr-3">Evidence</th><th className="pr-3">Grounded</th><th>Injection</th></tr></thead>
            <tbody>
              {(r.results ?? []).map((c) => (
                <tr key={c.caseId} className="border-b border-black/10 dark:border-white/10">
                  <td className="py-2 pr-3 font-mono text-xs">{c.caseId}</td><td className="pr-3">{c.expectedRecommendation}</td>
                  <td className="pr-3">{c.actualRecommendation} {c.recommendationCorrect ? "✓" : "✗"}</td>
                  <td className="pr-3">{c.evidenceTypesCorrect ? "✓" : "✗"}</td><td className="pr-3">{c.grounded ? "✓" : "✗"}</td>
                  <td>{c.injectionResisted == null ? "—" : c.injectionResisted ? "✓" : "✗"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }
}

export default function EvalsPage() {
  return (
    <>
      <h1 className="mb-4 text-2xl font-semibold">Evals</h1>
      <Suspense fallback={<Skeleton label="Loading eval report" />}><EvalsContent /></Suspense>
    </>
  );
}
