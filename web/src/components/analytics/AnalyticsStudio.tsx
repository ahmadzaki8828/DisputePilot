"use client";
// The /analytics dashboard: one AG Studio instance over disputes, drafts and submissions, with DisputePilot's
// theme, a custom "Deadline radar" widget and, when the backend has a live model, AG Studio's AI assistant (the
// Studio Agent Framework's built-in harness) driven by the app's own model provider through /analytics/agent.
// The host owns page tabs, view/edit mode and "reset"; Studio owns the report. Edits are kept in this browser.
import { useMemo, useRef, useState, useSyncExternalStore } from "react";
import { AgStudioAiModule, createAiHarness, enableStudioDevValidations, type AgAiHarnessSetup, type AgStudioApi, type AgStudioMode } from "ag-studio";
import { AgStudio, AgStudioProvider, createWidgets } from "ag-studio-react";
import type { AgReportState } from "ag-studio";
import { chatCompletionsAdapter } from "./chat-adapter";
import { DEADLINE_WIDGET, DeadlineWidget, type AnalyticsRegistry } from "./DeadlineWidget";
import { analyticsReport, PAGE_TITLES } from "./initial-report";
import { buildStudioData } from "./studio-data";
import { disputePilotStudioTheme } from "./theme";
import type { AgentInfo, AnalyticsDataset } from "./types";
import "./analytics.css";

if (process.env.NODE_ENV !== "production") enableStudioDevValidations();

type Report = AgReportState<AnalyticsRegistry>;

const STORAGE_KEY = "disputepilot:analytics-report:v1";

const PROMPT_STARTERS = [
  { label: "Costliest reason", prompt: "Which dispute reason costs me most this month? Add a widget that shows it." },
  { label: "Deadlines this week", prompt: "Add a chart of money at risk for disputes due in the next 7 days, by reason." },
  { label: "How often do we accept?", prompt: "How often does the AI recommend accepting a claim, and for which reasons?" },
];

function loadSaved(): Report | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as Report) : null;
  } catch {
    return null;
  }
}
function save(state: Report | null) {
  try {
    if (state) localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage blocked: keep working in memory.
  }
}

export function buildAi(agent: AgentInfo): AgAiHarnessSetup | undefined {
  if (!agent.enabled) return undefined;
  const adapter = chatCompletionsAdapter("/analytics/agent");
  return ({ api }) => createAiHarness(api, { adapter, promptStarters: PROMPT_STARTERS });
}

const noSubscription = () => () => {};

/** Opens or closes Studio's side panels (filters, edit, data, ai) in report state. */
export function withPanels(state: Report, panels: Record<string, { collapsed: boolean }>): Report {
  const next = { ...state.panels };
  for (const [k, v] of Object.entries(panels)) next[k] = { ...next[k], ...v };
  return { ...state, panels: next };
}

export function AnalyticsStudio(props: { data: AnalyticsDataset; licenseKey: string }) {
  // Studio and saved edits need the browser.
  const inBrowser = useSyncExternalStore(noSubscription, () => true, () => false);
  if (!inBrowser) return <div className="h-[70vh] animate-pulse rounded-lg border border-black/10 dark:border-white/15" aria-label="Loading dashboard" />;
  return <StudioDashboard {...props} />;
}

function StudioDashboard({ data, licenseKey }: { data: AnalyticsDataset; licenseKey: string }) {
  const apiRef = useRef<AgStudioApi<AnalyticsRegistry> | null>(null);
  const [initialState] = useState<Report>(() => withPanels(loadSaved() ?? analyticsReport, { filters: { collapsed: true } }));
  const [state, setState] = useState<Report>(initialState);
  const [mode, setMode] = useState<AgStudioMode>("view");
  const studioData = useMemo(() => buildStudioData(data), [data]);
  const ai = useMemo(() => buildAi(data.agent), [data.agent]);
  const widgets = useMemo(
    () =>
      createWidgets<AnalyticsRegistry>({
        additionalTypes: [
          {
            id: DEADLINE_WIDGET,
            label: "Deadline radar",
            icon: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="8" cy="8" r="6"/><path d="M8 4.5V8l2.5 1.5"/></svg>',
            dataMapping: {
              label: { type: "field", supportedRoles: ["category", "numeric"], required: true, requires: { cardinality: "one" } },
              hours: { type: "field", supportedRoles: ["numeric"], required: true, requires: { cardinality: "one" } },
              amount: { type: "field", supportedRoles: ["numeric"], requires: { cardinality: "one" } },
            },
            form: (params) =>
              params.createDefaults({
                dataMappingItems: [
                  { key: "label", label: "Item" },
                  { key: "hours", label: "Hours to deadline" },
                  { key: "amount", label: "Amount" },
                ],
              }),
            comp: DeadlineWidget,
            defaultSize: { width: 420, height: 320 },
            minSize: { width: 260, height: 160 },
            ai: {
              description:
                "Deadline radar: lists items soonest deadline first with a countdown pill (red under 48h, amber under 4 days). " +
                "Map label to disputes.paypalDisputeId, hours to disputes.hoursToDue (aggregation min) and amount to disputes.moneyAtRisk (sum).",
            },
          },
        ],
        menu: [{ label: "DisputePilot", widgetIds: [DEADLINE_WIDGET] }],
      }),
    [],
  );

  const pages = state.pages.map((p, i) => ({ id: p.id, title: PAGE_TITLES[p.id] ?? `Page ${i + 1}` }));
  const update = (next: (s: Report) => Report) => {
    const api = apiRef.current;
    if (api) api.setState(next(api.getState()));
  };

  return (
    <div className="dpa-root flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <div role="tablist" aria-label="Dashboard pages" className="flex flex-wrap gap-1">
          {pages.map((p) => (
            <button
              key={p.id}
              role="tab"
              aria-selected={state.selectedPageId === p.id}
              onClick={() => update((s) => ({ ...s, selectedPageId: p.id }))}
              className={`rounded-md px-3 py-1.5 text-sm ${state.selectedPageId === p.id ? "bg-indigo-600 text-white" : "border border-black/15 hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"}`}
            >
              {p.title}
            </button>
          ))}
        </div>
        <div className="ml-auto flex gap-2">
          {ai && (
            <button
              onClick={() => {
                update((s) => withPanels(s, { ai: { collapsed: false }, edit: { collapsed: true }, data: { collapsed: true }, filters: { collapsed: true } }));
                setMode("edit");
              }}
              className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white hover:bg-indigo-500"
            >
              Ask the agent
            </button>
          )}
          <button
            onClick={() => setMode((m) => (m === "edit" ? "view" : "edit"))}
            className="rounded-md border border-black/15 px-3 py-1.5 text-sm hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
            aria-pressed={mode === "edit"}
          >
            {mode === "edit" ? "Done editing" : "Edit dashboard"}
          </button>
          <button
            onClick={() => {
              save(null);
              const api = apiRef.current;
              if (api) {
                api.setState(analyticsReport);
                api.clearHistory();
              }
            }}
            className="rounded-md border border-black/15 px-3 py-1.5 text-sm hover:bg-black/5 dark:border-white/20 dark:hover:bg-white/10"
          >
            Reset dashboard
          </button>
        </div>
      </div>
      <div className="h-[78vh] min-h-[560px] overflow-hidden rounded-lg border border-black/10 dark:border-white/15">
        <AgStudioProvider licenseKey={licenseKey} modules={ai ? [AgStudioAiModule] : undefined}>
          <AgStudio<AnalyticsRegistry>
            style={{ height: "100%", width: "100%" }}
            theme={disputePilotStudioTheme}
            layout={{ widgetBorderEnabled: true }}
            data={studioData}
            widgets={widgets}
            ai={ai}
            initialState={initialState}
            mode={mode}
            onApiReady={(e) => {
              apiRef.current = e.api as unknown as AgStudioApi<AnalyticsRegistry>;
            }}
            onStateUpdated={(e) => {
              setState(e.state);
              save(e.state);
            }}
          />
        </AgStudioProvider>
      </div>
    </div>
  );
}
