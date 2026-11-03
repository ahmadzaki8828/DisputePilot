"use client";

import Link from "next/link";
import { useEffect, useMemo } from "react";
import { AgGridReact } from "ag-grid-react";
import { AllCommunityModule, ModuleRegistry, colorSchemeVariable, themeQuartz, type ColDef } from "ag-grid-community";
import type { DisputeSummary } from "@/lib/types";
import { DueBadge } from "./DueBadge";
import { RecommendationChip } from "./RecommendationChip";
import { InboxRefresh } from "./InboxRefresh";
import { awaitingAutoDraft } from "./DraftingNotice";
import { SimulatedTag } from "./ui";

ModuleRegistry.registerModules([AllCommunityModule]);

const theme = themeQuartz.withPart(colorSchemeVariable);
const money = (a: DisputeSummary["amount"]) => new Intl.NumberFormat("en-CA", { style: "currency", currency: a.currency }).format(a.value);
const statusLabel = (s: string) => s.replaceAll("_", " ").toLowerCase().replace(/^\w/, (c) => c.toUpperCase());

/** The summary has no allowed-actions list; a dispute still waiting on the seller with no draft is being drafted. */
const drafting = (d?: DisputeSummary) => !!d && !d.latestDraft && awaitingAutoDraft(d.status, ["PROVIDE_EVIDENCE"]);

export function InboxGrid({ rows }: { rows: DisputeSummary[] }) {
  useEffect(() => {
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => document.body.setAttribute("data-ag-theme-mode", mq.matches ? "dark" : "light");
    apply();
    mq.addEventListener("change", apply);
    return () => mq.removeEventListener("change", apply);
  }, []);

  const cols = useMemo<ColDef<DisputeSummary>[]>(
    () => [
      {
        headerName: "Dispute", field: "paypalDisputeId", minWidth: 190,
        cellRenderer: (p: { data?: DisputeSummary }) => p.data && (
          <span><Link className="font-medium underline" href={`/disputes/${p.data.id}`}>{p.data.paypalDisputeId}</Link>{p.data.simulated && <SimulatedTag />}</span>
        ),
      },
      { headerName: "Reason", field: "reasonLabel", minWidth: 170, filter: true },
      { headerName: "Amount", valueGetter: (p) => p.data?.amount.value, valueFormatter: (p) => (p.data ? money(p.data.amount) : ""), filter: "agNumberColumnFilter", width: 120 },
      {
        headerName: "Due", field: "sellerResponseDueAt", width: 150, sort: "asc",
        comparator: (a, b) => (a ? Date.parse(a) : Infinity) - (b ? Date.parse(b) : Infinity),
        cellRenderer: (p: { data?: DisputeSummary }) => p.data && <DueBadge dueAt={p.data.sellerResponseDueAt} />,
      },
      { headerName: "Status", field: "status", valueFormatter: (p) => statusLabel(String(p.value ?? "")), filter: true, minWidth: 220 },
      {
        headerName: "AI recommendation", width: 190, valueGetter: (p) => p.data?.latestDraft?.recommendation ?? "",
        cellRenderer: (p: { data?: DisputeSummary }) =>
          p.data?.latestDraft ? <RecommendationChip recommendation={p.data.latestDraft.recommendation} confidence={p.data.latestDraft.confidence} /> : <span className="opacity-60">{drafting(p.data) ? "Drafting…" : "No draft yet"}</span>,
      },
    ],
    [],
  );

  return (
    <>
      <InboxRefresh active={rows.some(drafting)} />
      {/* Phones: simple cards. Larger screens: the grid. */}
      <ul className="space-y-3 md:hidden" aria-label="Disputes">
        {rows.map((d) => (
          <li key={d.id} className="rounded-lg border border-black/15 p-3 dark:border-white/20">
            <div className="flex items-center justify-between">
              <Link className="font-medium underline" href={`/disputes/${d.id}`}>{d.paypalDisputeId}</Link>
              <DueBadge dueAt={d.sellerResponseDueAt} />
            </div>
            <p className="mt-1 text-sm">{d.reasonLabel} · {money(d.amount)}{d.simulated && <SimulatedTag />}</p>
            <div className="mt-2">{d.latestDraft ? <RecommendationChip recommendation={d.latestDraft.recommendation} confidence={d.latestDraft.confidence} /> : <span className="text-xs opacity-60">{drafting(d) ? "Drafting…" : "No draft yet"}</span>}</div>
          </li>
        ))}
      </ul>
      <div className="hidden md:block" style={{ width: "100%" }}>
        <AgGridReact<DisputeSummary> theme={theme} rowData={rows} columnDefs={cols} defaultColDef={{ sortable: true, resizable: true }} domLayout="autoHeight" getRowId={(p) => p.data.id} />
      </div>
    </>
  );
}
