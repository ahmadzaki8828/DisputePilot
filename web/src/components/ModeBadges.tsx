import { aiModeLabel, paypalModeLabel } from "@/lib/labels";
import type { SystemInfo } from "@/lib/types";

const pill = "inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium";

export function ModeBadges({ info }: { info: SystemInfo | null }) {
  if (!info) {
    return <span className={`${pill} border-amber-500/50 text-amber-700 dark:text-amber-300`}>Modes unavailable</span>;
  }
  return (
    <div className="flex flex-wrap gap-2" aria-label="Runtime modes">
      <span className={`${pill} border-sky-500/50 text-sky-800 dark:text-sky-300`}>{paypalModeLabel(info.paypalMode)}</span>
      <span title={[info.provider, info.model].filter(Boolean).join(" · ") || undefined} className={`${pill} border-violet-500/50 text-violet-800 dark:text-violet-300`}>{aiModeLabel(info.llmMode, info.model)}</span>
    </div>
  );
}
