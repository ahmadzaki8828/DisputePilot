# Demo video script (target 2:40, hard limit under 3:00)

Record a real screen capture of the app in a desktop browser. No logos other than DisputePilot's own, no music (or
royalty-free only), fictional merchant and carrier. Run the hosted demo, or `docker compose up --build` locally;
press **Reset demo** first so the inbox starts clean.

1. **Problem (15 s).** "A $180 order, the buyer says it never arrived, 10 days to respond. Most small sellers either
   miss it or upload the wrong thing."
2. **Webhook lands (20 s).** Click **Simulate a dispute** (Item not received). A `CUSTOMER.DISPUTE.CREATED` event
   arrives; the inbox shows the dispute with a red "due in 9d" countdown and the **Simulated** tag.
3. **Draft is already there (50 s).** The draft reads **FIGHT · 0.9x**, evidence `PROOF_OF_FULFILLMENT` with carrier and
   tracking number, delivered with signature. Hover a citation chip: the shipment record highlights. Validator: grounded.
   Point at the AI badge: it names the provider and model, or says the response is recorded.
4. **Human in the loop (30 s).** Edit one sentence, **Save edits**, **Approve**, **Submit evidence…**. The confirm modal
   shows the exact payload and the idempotency key. Submit; the status changes to Under review and the audit trail grows.
5. **Honesty (15 s).** Simulate **Unauthorised transaction**. The agent recommends **ACCEPT** and explains why fighting
   would waste the seller's time.
6. **Trust (20 s).** The **Evals** page with the real numbers (say the run's provider and model aloud). Show the
   architecture diagram from the README for about 10 seconds.
7. **Try it (10 s).** "The hosted demo link and a one-command local run are in the description."

Before recording: check the header badges tell the truth for the run you are filming (live model vs recorded), do not
claim a live model if the badge says Recorded, and keep the real sandbox keys off screen.
