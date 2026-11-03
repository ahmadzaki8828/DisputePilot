import { Suspense } from "react";
import { Placeholder } from "@/components/Placeholder";

async function DisputeContent({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <Placeholder title={`Dispute ${id}`} />;
}

export default function DisputePage({ params }: { params: Promise<{ id: string }> }) {
  return (
    <Suspense fallback={<Placeholder title="Dispute" />}>
      <DisputeContent params={params} />
    </Suspense>
  );
}
