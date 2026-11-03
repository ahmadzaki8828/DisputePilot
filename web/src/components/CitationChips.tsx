"use client";

import { createContext, useContext, useState } from "react";
import { isHighlighted } from "@/lib/citations";
import type { Citation } from "@/lib/types";

const Ctx = createContext<{ active: string | null; setActive: (id: string | null) => void }>({ active: null, setActive: () => {} });

export function CitationHighlightProvider({ children }: { children: React.ReactNode }) {
  const [active, setActive] = useState<string | null>(null);
  return <Ctx.Provider value={{ active, setActive }}>{children}</Ctx.Provider>;
}

export function CitationChip({ citation }: { citation: Citation }) {
  const { active, setActive } = useContext(Ctx);
  const on = isHighlighted(citation.id, active);
  return (
    <button
      type="button"
      aria-pressed={on}
      title={citation.id}
      className={`rounded-full border px-2 py-0.5 text-xs ${on ? "border-indigo-600 bg-indigo-100 dark:bg-indigo-900" : "border-black/20 dark:border-white/25"}`}
      onMouseEnter={() => setActive(citation.id)}
      onMouseLeave={() => setActive(null)}
      onFocus={() => setActive(citation.id)}
      onBlur={() => setActive(null)}
      onClick={() => setActive(on ? null : citation.id)}
    >
      {citation.label}
    </button>
  );
}

export function SourceBlock({ id, children, className = "" }: { id: string; children: React.ReactNode; className?: string }) {
  const { active } = useContext(Ctx);
  const on = isHighlighted(id, active);
  return (
    <div data-source={id} data-highlighted={on} className={`rounded-md p-2 transition-colors ${on ? "bg-yellow-200/70 ring-2 ring-yellow-500 dark:bg-yellow-900/50" : ""} ${className}`}>
      {children}
    </div>
  );
}
