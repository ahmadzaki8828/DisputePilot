import Link from "next/link";
import { Suspense } from "react";
import { connection } from "next/server";
import { DisputeView } from "@/components/DisputeView";
import { ErrorPanel, Skeleton } from "@/components/ui";
import { safe } from "@/lib/safe";
import { getAudit, getDispute, getLatestDraft } from "@/lib/server-api";

async function DisputeContent({ params }: { params: Promise<{ id: string }> }) {
  await connection();
  const { id } = await params;
  const r = await safe(() => Promise.all([getDispute(id), getLatestDraft(id), getAudit(id).catch(() => [])]));
  if (!r.ok) {
    return r.status === 404
      ? <ErrorPanel title="Dispute not found" message="It may have been removed by a demo reset." retryHref="/" />
      : <ErrorPanel title="Could not load this dispute" message={r.error} retryHref={`/disputes/${id}`} />;
  }
  const [raw, draft, audit] = r.data;
  // The browser never needs buyer contact details; keep them server-side.
  const order = raw.caseFile.order;
  const dispute = order
    ? { ...raw, caseFile: { ...raw.caseFile, order: { ...order, buyerEmail: undefined, buyerPhone: undefined, shippingAddress: undefined } } }
    : raw;
  return <DisputeView key={draft?.id ?? "none"} dispute={dispute} initialDraft={draft} audit={audit} />;
}

export default function DisputePage({ params }: { params: Promise<{ id: string }> }) {
  return (
    <>
      <Link href="/" className="mb-3 inline-block text-sm underline">← Inbox</Link>
      <Suspense fallback={<Skeleton label="Loading dispute" />}>
        <DisputeContent params={params} />
      </Suspense>
    </>
  );
}
