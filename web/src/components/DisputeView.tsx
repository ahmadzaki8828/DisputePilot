"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { bff } from "@/lib/client";
import { aiModeLabel } from "@/lib/labels";
import type { AuditEntry, DisputeDetail, Draft, SubmitAction } from "@/lib/types";
import { CitationChip, CitationHighlightProvider, SourceBlock } from "./CitationChips";
import { DraftingNotice, awaitingAutoDraft } from "./DraftingNotice";
import { DueBadge } from "./DueBadge";
import { LinkOrder } from "./LinkOrder";
import { NOTES_MAX, NotesEditor } from "./NotesEditor";
import { RecommendationChip } from "./RecommendationChip";
import { SubmitModal } from "./SubmitModal";
import { SimulatedTag, errMessage } from "./ui";

const fmt = (iso?: string | null) => (iso ? new Date(iso).toLocaleString("en-CA", { dateStyle: "medium", timeStyle: "short" }) : "—");
const card = "rounded-lg border border-black/15 p-4 dark:border-white/20";

/** FIGHT answers with evidence and ACCEPT accepts the claim. An offer needs an amount only PayPal can take. */
function submitActionFor(d: Draft): SubmitAction | null {
  if (d.recommendation === "ACCEPT") return "ACCEPT_CLAIM";
  if (d.recommendation === "FIGHT") return "PROVIDE_EVIDENCE";
  return null;
}

export function DisputeView({ dispute, initialDraft, audit }: { dispute: DisputeDetail; initialDraft: Draft | null; audit: AuditEntry[] }) {
  const router = useRouter();
  const [draft, setDraft] = useState<Draft | null>(initialDraft);
  const [notes, setNotes] = useState(initialDraft?.notes ?? "");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [override, setOverride] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const { caseFile } = dispute;

  async function act<T>(name: string, fn: () => Promise<T>, done?: (r: T) => void) {
    setBusy(name);
    setError(null);
    setInfo(null);
    try {
      const r = await fn();
      done?.(r);
      router.refresh();
    } catch (e) {
      setError(errMessage(e));
    } finally {
      setBusy(null);
    }
  }
  const adopt = (d: Draft) => { setDraft(d); setNotes(d.notes); };

  const dirty = draft !== null && notes !== draft.notes;
  const action = draft ? submitActionFor(draft) : null;
  const allowedByPayPal = action !== null && dispute.allowedActions.includes(action);
  const canApprove = draft && !dirty && (draft.status === "DRAFT" || draft.status === "NEEDS_REVIEW") && (draft.validation.passed || override) && notes.length <= NOTES_MAX;
  const canSubmit = draft && draft.status === "APPROVED" && allowedByPayPal;

  return (
    <CitationHighlightProvider>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold">
          {dispute.paypalDisputeId}{dispute.simulated && <SimulatedTag />}
        </h1>
        <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          <span>{dispute.reasonLabel}</span>
          <span>{new Intl.NumberFormat("en-CA", { style: "currency", currency: dispute.amount.currency }).format(dispute.amount.value)}</span>
          <span className="rounded bg-black/5 px-2 py-0.5 dark:bg-white/10">{dispute.status.replaceAll("_", " ")}</span>
          {dispute.stage && <span>Stage: {dispute.stage}</span>}
          <DueBadge dueAt={dispute.sellerResponseDueAt} />
        </p>
      </header>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* Left: timeline + case file */}
        <div className="space-y-4">
          <section className={card} aria-labelledby="timeline-h">
            <h2 id="timeline-h" className="font-semibold">Dispute thread</h2>
            <p className="text-xs opacity-70">Buyer-written text is untrusted data. The agent treats it as evidence, never as instructions.</p>
            <SourceBlock id={`dispute:${dispute.paypalDisputeId}`}>
              <ul className="mt-2 space-y-2 text-sm">
                {dispute.messages.map((m, i) => (
                  <li key={i}><span className="font-medium">{m.postedBy}</span> <span className="text-xs opacity-60">{fmt(m.postedAt)}</span><p className="whitespace-pre-wrap">{m.content}</p></li>
                ))}
                {dispute.messages.length === 0 && <li className="opacity-70">No messages.</li>}
              </ul>
              <p className="mt-2 text-xs opacity-70">PayPal asks for: {dispute.requestedEvidence.join(", ") || "—"} · Allowed actions: {dispute.allowedActions.join(", ") || "none"}</p>
            </SourceBlock>
          </section>

          <section className={card} aria-labelledby="case-h">
            <h2 id="case-h" className="font-semibold">Case file (your records)</h2>
            {caseFile.order ? (
              <SourceBlock id={`order:${caseFile.order.id}`} className="mt-2 text-sm">
                <h3 className="font-medium">Order {caseFile.order.id}</h3>
                <p>{caseFile.order.items?.map((i) => `${i.quantity}× ${i.name}`).join(", ")} · {caseFile.order.status}</p>
                <p className="text-xs opacity-70">Placed {fmt(caseFile.order.placedAt)} · Transaction {caseFile.order.paypalTransactionId}</p>
                {caseFile.order.refunds?.map((r) => <p key={r.id} className="text-xs">Refund {r.id}: {r.amount?.value} {r.amount?.currency}</p>)}
              </SourceBlock>
            ) : (
              <LinkOrder transactionId={dispute.disputedTransactionId} />
            )}
            {(caseFile.shipments ?? []).map((s) => (
              <SourceBlock key={s.id} id={`shipment:${s.id}`} className="mt-2 text-sm">
                <h3 className="font-medium">Shipment {s.id}</h3>
                <p>{s.carrier} · <span className="font-mono">{s.trackingNumber}</span> · {s.status}</p>
                <p className="text-xs opacity-70">Delivered {fmt(s.deliveredAt)}{s.signedBy ? `, signed by ${s.signedBy}` : ", no signature"}</p>
              </SourceBlock>
            ))}
            {(caseFile.customerMessages ?? []).map((m) => (
              <SourceBlock key={m.id} id={`msg:${m.id}`} className="mt-2 text-sm">
                <h3 className="font-medium">{m.from === "BUYER" ? "Buyer message (untrusted)" : "Your message"} {m.id}</h3>
                <p className="whitespace-pre-wrap">{m.body}</p>
              </SourceBlock>
            ))}
            {caseFile.policy && (() => { const policy = caseFile.policy; return (
              <div className="mt-2 space-y-1 text-sm">
                <h3 className="font-medium">Store policy</h3>
                {(["returns", "shipping", "refunds", "productDescriptions"] as const).map((k) => policy[k] && (
                  <SourceBlock key={k} id={`policy:${k}`}><span className="font-medium capitalize">{k}: </span>{policy[k]}</SourceBlock>
                ))}
              </div>
            ); })()}
          </section>
        </div>

        {/* Right: draft */}
        <section className={card} aria-labelledby="draft-h">
          <h2 id="draft-h" className="font-semibold">AI draft</h2>
          {!draft ? (
            awaitingAutoDraft(dispute.status, dispute.allowedActions) ? (
              <DraftingNotice busy={busy !== null} onGenerate={() => act("gen", () => bff<Draft>("POST", `/api/disputes/${dispute.id}/drafts`), adopt)} />
            ) : (
              <div className="mt-2 text-sm">
                <p>No draft{dispute.status === "WAITING_FOR_SELLER_RESPONSE" ? " yet" : ": PayPal does not currently ask for a response"}.</p>
                <button type="button" disabled={busy !== null} className="mt-2 rounded bg-indigo-600 px-3 py-1.5 text-white disabled:opacity-50" onClick={() => act("gen", () => bff<Draft>("POST", `/api/disputes/${dispute.id}/drafts`), adopt)}>
                  {busy === "gen" ? "Drafting…" : "Generate draft"}
                </button>
              </div>
            )
          ) : (
            <div className="mt-2 space-y-3">
              <div className="flex flex-wrap items-center gap-2">
                <RecommendationChip recommendation={draft.recommendation} confidence={draft.confidence} />
                <span className="rounded border px-2 py-0.5 text-xs">{draft.status.replace("_", " ")}</span>
                <span className="rounded border border-violet-500/50 px-2 py-0.5 text-xs" title={[draft.provider, draft.model, draft.promptVersion && `prompt ${draft.promptVersion}`].filter(Boolean).join(" · ")}>{aiModeLabel(draft.llmMode, draft.model)}</span>
                <span className="text-xs opacity-60">v{draft.version}{draft.editedByHuman ? " · edited" : ""}</span>
              </div>
              <div role="meter" aria-label="Confidence" aria-valuemin={0} aria-valuemax={1} aria-valuenow={draft.confidence} className="h-2 w-full rounded bg-black/10 dark:bg-white/15">
                <div className="h-2 rounded bg-indigo-600" style={{ width: `${Math.round(draft.confidence * 100)}%` }} />
              </div>
              {draft.llmMode !== "live" && <p className="text-xs opacity-70">{draft.llmMode === "sample" ? "Hand-written sample: no model produced this draft." : "Replayed from a stored model response, not generated just now."}{draft.provider ? ` (${draft.provider}${draft.model ? `, ${draft.model}` : ""})` : ""}</p>}
              {draft.llmMode === "live" && draft.provider && <p className="text-xs opacity-70">Drafted live by {draft.provider}{draft.model ? ` (${draft.model})` : ""}.</p>}
              <p className="text-sm">{draft.rationale}</p>
              <div className="flex flex-wrap gap-1.5" aria-label="Citations">{draft.citations.map((c) => <CitationChip key={c.id} citation={c} />)}</div>

              <div>
                <h3 className="text-sm font-medium">Evidence to submit</h3>
                {draft.evidence.length === 0 ? <p className="text-sm opacity-70">None{draft.recommendation === "ACCEPT" ? " (accepting the claim)" : ""}.</p> : (
                  <ul className="mt-1 space-y-1 text-sm">
                    {draft.evidence.map((e, i) => (
                      <li key={i} className="rounded bg-black/5 p-2 dark:bg-white/10">
                        <span className="font-mono text-xs">{e.evidence_type}</span>
                        {e.evidence_info?.tracking_info?.map((t, j) => <p key={j}>{t.carrier_name_other ?? t.carrier_name} · <span className="font-mono">{t.tracking_number}</span></p>)}
                        {e.evidence_info?.refund_ids?.map((r, j) => <p key={j}>Refund <span className="font-mono">{r.refund_id}</span></p>)}
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              <NotesEditor value={notes} onChange={setNotes} disabled={draft.status === "APPROVED" || draft.status === "SUBMITTED"} />

              {draft.missingInfo && draft.missingInfo.length > 0 && (
                <div className="text-sm"><h3 className="font-medium">Missing information</h3><ul className="list-disc pl-5">{draft.missingInfo.map((m) => <li key={m}>{m}</li>)}</ul></div>
              )}
              <div className={`rounded p-2 text-sm ${draft.validation.passed ? "bg-emerald-50 text-emerald-900 dark:bg-emerald-950 dark:text-emerald-100" : "bg-red-50 text-red-900 dark:bg-red-950 dark:text-red-100"}`}>
                {draft.validation.passed ? "✓ Validator: every claim is grounded in your records." : (
                  <>
                    <p className="font-medium">Validator found problems:</p>
                    <ul className="list-disc pl-5">{draft.validation.issues.map((i, n) => <li key={n}><span className="font-mono text-xs">{i.code}</span> {i.message}</li>)}</ul>
                    <label className="mt-2 flex items-center gap-2"><input type="checkbox" checked={override} onChange={(e) => setOverride(e.target.checked)} /> I reviewed these issues and want to approve anyway</label>
                  </>
                )}
              </div>

              {busy === "gen" && <p role="status" className="flex items-center gap-2 text-sm"><span aria-hidden className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-indigo-600 border-t-transparent" />Drafting… a live model can take about 30 seconds.</p>}
              {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
              {info && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-300">{info}</p>}
              {dirty && <p className="text-xs opacity-70">Save your edits before approving.</p>}

              <div className="flex flex-wrap gap-2">
                <button type="button" disabled={busy !== null || draft.status === "APPROVED" || draft.status === "SUBMITTED"} className="rounded border px-3 py-1.5 text-sm disabled:opacity-50" onClick={() => act("gen", () => bff<Draft>("POST", `/api/disputes/${dispute.id}/drafts`), adopt)}>
                  {busy === "gen" ? "Drafting…" : "Regenerate"}
                </button>
                <button type="button" disabled={busy !== null || !dirty || notes.length > NOTES_MAX} className="rounded border px-3 py-1.5 text-sm disabled:opacity-50" onClick={() => act("save", () => bff<Draft>("PATCH", `/api/drafts/${draft.id}`, { notes, evidence: draft.evidence }), adopt)}>
                  Save edits
                </button>
                <button type="button" disabled={busy !== null || !canApprove} className="rounded bg-emerald-600 px-3 py-1.5 text-sm text-white disabled:opacity-50" onClick={() => act("approve", () => bff<Draft>("POST", `/api/drafts/${draft.id}/approve`, { overrideValidation: override }), adopt)}>
                  Approve
                </button>
                {action && (
                  <button type="button" disabled={busy !== null || !canSubmit} className="rounded bg-indigo-600 px-3 py-1.5 text-sm text-white disabled:opacity-50" onClick={() => setConfirming(true)}>
                    {action === "ACCEPT_CLAIM" ? "Accept claim…" : "Submit evidence…"}
                  </button>
                )}
              </div>
              {!action && <p className="text-xs opacity-70">This draft recommends {draft.recommendation.replace("_", " ")}. DisputePilot submits evidence or accepts a claim; an offer needs an amount you set in PayPal.</p>}
              {draft.status === "APPROVED" && !allowedByPayPal && <p className="text-xs opacity-70">PayPal does not currently allow this action on the dispute.</p>}
            </div>
          )}
        </section>
      </div>

      <section className={`${card} mt-6`} aria-labelledby="audit-h">
        <h2 id="audit-h" className="font-semibold">Audit trail</h2>
        {audit.length === 0 ? <p className="mt-2 text-sm opacity-70">No entries yet.</p> : (
          <ol className="mt-2 space-y-1 text-sm">
            {audit.map((a) => <li key={a.id}><span className="text-xs opacity-60">{fmt(a.at)}</span> <span className="font-mono text-xs">{a.actor}</span> {a.action}</li>)}
          </ol>
        )}
      </section>

      {confirming && draft && action && (
        <SubmitModal
          draftId={draft.id}
          action={action}
          evidence={draft.evidence}
          notes={notes}
          onCancel={() => setConfirming(false)}
          onConfirm={async (key) => {
            await bff("POST", `/api/drafts/${draft.id}/submit`, undefined, { "Idempotency-Key": key });
            setConfirming(false);
            setDraft({ ...draft, status: "SUBMITTED" });
            setInfo("Submitted to PayPal. The audit trail below records it.");
            router.refresh();
          }}
        />
      )}
    </CitationHighlightProvider>
  );
}
