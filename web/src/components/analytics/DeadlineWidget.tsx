"use client";
// Custom AG Studio widget: "Deadline radar". One row per item (usually a dispute), ordered by how soon the seller
// response is due, with a countdown pill coloured like the inbox's due badge (red under 48h, amber under 4 days)
// and the money at stake. Studio applies page filters and cross-filters to its query like any built-in widget.
import { useEffect, useRef, useState } from "react";
import type { AgBaseRegistry, AgBaseWidgetDefinition, AgDefaultRegistry, AgWidgetDataFormat, AgWidgetFieldReference, AgWidgetParams } from "ag-studio";

export const DEADLINE_WIDGET = "deadline-radar" as const;

export interface DeadlineWidgetDef {
  type: typeof DEADLINE_WIDGET;
  dataMapping: {
    label: AgWidgetFieldReference[];
    hours: AgWidgetFieldReference[];
    amount: AgWidgetFieldReference[];
  };
  format?: AgWidgetDataFormat<Record<string, never>>;
}

export interface AnalyticsRegistry extends AgBaseRegistry {
  // Studio's own widgets plus the deadline radar.
  widgets: readonly (AgDefaultRegistry["widgets"][number] | AgBaseWidgetDefinition<typeof DEADLINE_WIDGET, DeadlineWidgetDef>)[];
}

export type DeadlineRow = { label: string; hours: number | null; amount: string };

const MAX_ROWS = 12;

/** Soonest first; items with no deadline last. Pure, so it is unit-tested. */
export function orderByDeadline<T extends { hours: number | null }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => (a.hours ?? Infinity) - (b.hours ?? Infinity));
}

export function countdownLabel(hours: number | null): string {
  if (hours == null) return "No deadline";
  if (hours < 0) return `Overdue ${Math.round(-hours)}h`;
  if (hours < 48) return `${Math.round(hours)}h left`;
  return `${Math.round(hours / 24)}d left`;
}

export function urgency(hours: number | null): "overdue" | "urgent" | "soon" | "ok" {
  if (hours == null) return "ok";
  if (hours < 0) return "overdue";
  if (hours < 48) return "urgent";
  return hours < 96 ? "soon" : "ok";
}

export function DeadlineWidget({ dataMapping, widgetApi }: AgWidgetParams<DeadlineWidgetDef>) {
  const [rows, setRows] = useState<DeadlineRow[]>([]);
  const loaded = useRef(false);

  useEffect(() => {
    let cancelled = false;
    const label = dataMapping.label?.at(0);
    const hours = dataMapping.hours?.at(0);
    const amount = dataMapping.amount?.at(0);
    if (!label || !hours) {
      widgetApi.setDisplayState("incompleteDataMapping");
      return;
    }
    (async () => {
      widgetApi.setDisplayState("loading", { prominent: !loaded.current });
      const queryFields = [label, hours, ...(amount ? [amount] : [])];
      const response = await widgetApi.getData({ fields: queryFields });
      if (cancelled) return;
      const next = orderByDeadline(
        response.results.rows.map((r) => ({
          label: widgetApi.formatFieldValue(label, r[label.key]) ?? String(r[label.key] ?? ""),
          hours: typeof r[hours.key] === "number" ? (r[hours.key] as number) : null,
          amount: amount ? widgetApi.formatFieldValue(amount, r[amount.key]) ?? "" : "",
        })),
      ).slice(0, MAX_ROWS);
      loaded.current = next.length > 0;
      setRows(next);
      widgetApi.setDisplayState(next.length > 0 ? "displayed" : "noData");
    })().catch(() => {
      if (!cancelled) widgetApi.setDisplayState("noData");
    });
    return () => {
      cancelled = true;
    };
  }, [dataMapping, widgetApi]);

  const span = Math.max(168, ...rows.map((r) => (r.hours != null && r.hours > 0 ? r.hours : 0)));
  return (
    <ul className="dpa-radar" aria-label="Disputes by time left to respond">
      {rows.map((r, i) => {
        const u = urgency(r.hours);
        const width = r.hours == null ? 0 : Math.max(3, Math.min(100, ((r.hours > 0 ? r.hours : 0) / span) * 100));
        return (
          <li key={`${r.label}-${i}`} className="dpa-radar-row">
            <span className="dpa-radar-label" title={r.label}>{r.label}</span>
            <span className="dpa-radar-track" aria-hidden="true">
              <span className={`dpa-radar-bar dpa-${u}`} style={{ width: `${u === "overdue" ? 100 : width}%` }} />
            </span>
            <span className={`dpa-radar-pill dpa-${u}`}>{countdownLabel(r.hours)}</span>
            <span className="dpa-radar-amount">{r.amount}</span>
          </li>
        );
      })}
    </ul>
  );
}
