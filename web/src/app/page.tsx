import { Suspense } from "react";
import { connection } from "next/server";
import { InboxGrid } from "@/components/InboxGrid";
import { TourBanner } from "@/components/TourBanner";
import { EmptyState, ErrorPanel, Skeleton } from "@/components/ui";
import { safe } from "@/lib/safe";
import { getDisputes } from "@/lib/server-api";

async function InboxContent() {
  await connection();
  const r = await safe(getDisputes);
  if (!r.ok) return <ErrorPanel title="Could not load disputes" message={r.error} retryHref="/" />;
  if (r.data.length === 0) {
    return <EmptyState title="No disputes yet">Click <strong>Simulate a dispute</strong> above to create one.</EmptyState>;
  }
  return <InboxGrid rows={r.data} />;
}

export default function InboxPage() {
  return (
    <>
      <h1 className="mb-4 text-2xl font-semibold">Inbox</h1>
      <TourBanner />
      <Suspense fallback={<Skeleton label="Loading disputes" />}>
        <InboxContent />
      </Suspense>
    </>
  );
}
