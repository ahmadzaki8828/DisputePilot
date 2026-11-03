import type { Recommendation } from "@/lib/types";

const styles: Record<string, string> = {
  FIGHT: "bg-emerald-100 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-200",
  ACCEPT: "bg-sky-100 text-sky-900 dark:bg-sky-950 dark:text-sky-200",
  OFFER: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  NEEDS_INFO: "bg-neutral-200 text-neutral-900 dark:bg-neutral-800 dark:text-neutral-200",
};

export function RecommendationChip({ recommendation, confidence }: { recommendation: Recommendation; confidence: number }) {
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs font-semibold ${styles[recommendation] ?? styles.NEEDS_INFO}`}>
      {recommendation.replace("_", " ")} · {confidence.toFixed(2)}
    </span>
  );
}
