"use client";

import { dueInfo, type DueTone } from "@/lib/due";

const tones: Record<DueTone, string> = {
  urgent: "bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-200",
  soon: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-200",
  ok: "bg-neutral-100 text-neutral-800 dark:bg-neutral-800 dark:text-neutral-200",
  none: "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400",
};

export function DueBadge({ dueAt, now }: { dueAt: string | null | undefined; now?: Date }) {
  const { label, tone } = dueInfo(dueAt, now);
  return (
    <span data-tone={tone} suppressHydrationWarning className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${tones[tone]}`}>
      {label}
    </span>
  );
}
