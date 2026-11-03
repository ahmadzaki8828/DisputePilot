export default function AboutPage() {
  const h = "mt-6 text-lg font-semibold";
  return (
    <article className="max-w-3xl space-y-2 text-sm leading-relaxed">
      <h1 className="text-2xl font-semibold">About DisputePilot</h1>
      <p>DisputePilot helps small merchants answer PayPal disputes before the deadline. An AI agent drafts the response from the merchant&apos;s own records; a person reviews, edits and approves every submission.</p>
      <h2 className={h}>How it works</h2>
      <ol className="list-decimal pl-5">
        <li>A dispute webhook arrives and DisputePilot re-fetches the dispute from PayPal.</li>
        <li>The agent reads your order, shipment, customer messages and store policy, with personal data redacted.</li>
        <li>It proposes fight, accept or offer, with the exact evidence payload and a note, each fact cited to a record.</li>
        <li>A deterministic validator rejects anything ungrounded. You approve, then submit with an idempotency key.</li>
      </ol>
      <h2 className={h}>Safety design</h2>
      <ul className="list-disc pl-5">
        <li>Human in the loop: nothing is submitted without an approved draft and a click.</li>
        <li>Grounding: tracking numbers, refund IDs and citations must exist in your records.</li>
        <li>Buyer-written text is treated as untrusted data, never as instructions.</li>
        <li>Sandbox only: the app never talks to live PayPal.</li>
      </ul>
      <h2 className={h}>AI and PayPal modes</h2>
      <p>The badges in the header always say what is running: PayPal simulated or sandbox, and whether AI output is live from a named model, recorded real responses, or hand-written samples. Simulated disputes carry a Simulated tag.</p>
      <h2 className={h}>Demo data</h2>
      <p>The merchant (Maple &amp; Thread Co.), buyers and carrier (Northline Courier) are fictional.</p>
    </article>
  );
}
