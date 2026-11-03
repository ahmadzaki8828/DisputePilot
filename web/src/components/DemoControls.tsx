"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { bff } from "@/lib/client";
import type { DemoTemplate } from "@/lib/types";
import { errMessage } from "./ui";

const TEMPLATES: Array<[DemoTemplate, string]> = [
  ["ITEM_NOT_RECEIVED", "Item not received"],
  ["NOT_AS_DESCRIBED", "Not as described"],
  ["UNAUTHORISED", "Unauthorised transaction"],
  ["DUPLICATE_CHARGE", "Duplicate charge"],
  ["PROMPT_INJECTION", "Prompt-injection attempt"],
];

export function DemoControls() {
  const router = useRouter();
  const [template, setTemplate] = useState<DemoTemplate>("ITEM_NOT_RECEIVED");
  const [busy, setBusy] = useState<null | "create" | "reset">(null);
  const [msg, setMsg] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  async function run(kind: "create" | "reset") {
    setBusy(kind);
    setMsg(null);
    try {
      if (kind === "create") {
        await bff("POST", "/api/demo/disputes", { template });
        setMsg({ kind: "ok", text: "Simulated dispute created. It went through the same webhook → agent pipeline as a real one." });
      } else {
        await bff("POST", "/api/demo/reset");
        setMsg({ kind: "ok", text: "Demo reset to the starter set." });
      }
      router.refresh();
    } catch (e) {
      setMsg({ kind: "error", text: errMessage(e) });
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="mt-3 flex flex-wrap items-center gap-2">
      <label className="sr-only" htmlFor="demo-template">Dispute template</label>
      <select id="demo-template" value={template} onChange={(e) => setTemplate(e.target.value as DemoTemplate)} className="rounded border border-black/20 bg-transparent px-2 py-1.5 text-sm dark:border-white/25">
        {TEMPLATES.map(([v, l]) => <option key={v} value={v} className="text-black">{l}</option>)}
      </select>
      <button type="button" disabled={busy !== null} onClick={() => run("create")} className="rounded bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50">
        {busy === "create" ? "Creating…" : "Simulate a dispute"}
      </button>
      <button type="button" disabled={busy !== null} onClick={() => run("reset")} className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
        {busy === "reset" ? "Resetting…" : "Reset demo"}
      </button>
      {msg && <p role={msg.kind === "error" ? "alert" : "status"} className={`text-sm ${msg.kind === "error" ? "text-red-600" : "text-emerald-700 dark:text-emerald-300"}`}>{msg.text}</p>}
    </div>
  );
}
