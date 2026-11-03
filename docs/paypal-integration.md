# PayPal integration

How DisputePilot talks to PayPal, and why it is built this way. Everything here is **sandbox only** —
`SandboxGuard` refuses to start the app against any host that is not `*.sandbox.paypal.com`, and the shell
helpers in `scripts/sandbox/` refuse to run against one. There is no live mode.

## The two kinds of dispute, one pipeline

| | Real sandbox dispute | Simulated dispute |
|---|---|---|
| ID | whatever PayPal assigns | starts `DEMO-` |
| Served by | `HttpPayPalDisputesClient` | `SimulatedPayPalDisputesClient` |
| Needs keys | yes | no |
| Labelled in the UI | — | **Simulated** |

`RoutingPayPalDisputesClient` sends `DEMO-` IDs to the simulator and everything else to the sandbox, so a
simulated dispute travels the same route as a real one: webhook → `webhook_events` → outbox worker →
`disputes` → agent → human approval → submit. `listDisputes` merges both, and if the sandbox is unreachable
it returns the simulated half rather than an empty inbox.

`PAYPAL_MODE=mock` uses the simulator alone and makes **no network calls at all** — that is the path a judge
gets from a clean clone with no accounts. `PAYPAL_MODE=sandbox` uses the routing client.

## Endpoints used

| Call | Endpoint | Why |
|---|---|---|
| Auth | `POST /v1/oauth2/token` (Basic `client_id:secret`, `grant_type=client_credentials`) | every other call needs a bearer token |
| List | `GET /v1/customer/disputes` (`dispute_state`, `update_time_after`, `page_size`, `next_page_token`) | reconciliation: find disputes PayPal touched since our cursor |
| Details | `GET /v1/customer/disputes/{id}` | the source of truth for status, requested evidence, deadline and allowed actions |
| Submit evidence | `POST /v1/customer/disputes/{id}/provide-evidence` (`multipart/form-data`) | the merchant's answer: tracking, delivery proof, documents |
| Accept | `POST /v1/customer/disputes/{id}/accept-claim` | when fighting is the wrong call (often `UNAUTHORISED`) |
| Offer | `POST /v1/customer/disputes/{id}/make-offer` | partial refund instead of a full loss |
| Message | `POST /v1/customer/disputes/{id}/send-message` | reply on the dispute thread |
| Verify webhook | `POST /v1/notifications/verify-webhook-signature` | authenticates every delivery |
| Sandbox: require evidence | `POST /v1/customer/disputes/{id}/require-evidence` `{"action":"SELLER_EVIDENCE"}` | moves `UNDER_REVIEW` → `WAITING_FOR_SELLER_RESPONSE` so a demo can be replayed |
| Sandbox: adjudicate | `POST /v1/customer/disputes/{id}/adjudicate` `{"adjudication_outcome":"SELLER_FAVOR"}` | decide a dispute to show the outcome |

Webhook events consumed: `CUSTOMER.DISPUTE.CREATED`, `CUSTOMER.DISPUTE.UPDATED`, `CUSTOMER.DISPUTE.RESOLVED`.

### Provide evidence is multipart

The JSON goes in a part named `input`, with documents as further parts (`file1`, `file2`, …):

```
Content-Disposition: form-data; name="input"
Content-Type: application/json

{"evidences":[{"evidence_type":"PROOF_OF_FULFILLMENT",
  "evidence_info":{"tracking_info":[{"carrier_name":"OTHER",
    "carrier_name_other":"Northline Courier","tracking_number":"NLC4417002931"}]},
  "notes":"Delivered 18 Oct, signed for by the buyer."}]}
```

The carrier is fictional, so it is sent as `carrier_name: OTHER` plus the free-text `carrier_name_other`. No
real carrier, bank or brand name appears anywhere in this project.

Evidence can only be provided while the dispute is `WAITING_FOR_SELLER_RESPONSE`, and DisputePilot prefers the
evidence types PayPal itself lists in `evidences[]` with `source = REQUESTED_FROM_SELLER` over the playbook's
defaults for the reason code.

## Reliability design

**PayPal is the source of truth.** A webhook payload is a trigger and nothing more: the outbox worker always
re-fetches `GET /v1/customer/disputes/{id}` before writing a row. Nothing from a delivery body is trusted into
the `disputes` table, and buyer-written message text is carried as data only — never as instructions, in
prompts or in code.

**Allowed actions come from PayPal.** `PayPalDisputeMapper` derives them from `links[].rel`, so DisputePilot
never guesses that an action is available from a hard-coded state machine.

**At-least-once in, exactly-once effect.**

1. `POST /webhooks/paypal` verifies the signature, stores the event and returns 200 immediately. No admin
   token — PayPal cannot send one, and the signature is what authenticates the delivery.
2. `webhook_events.paypal_event_id` is unique, so a repeat delivery is a no-op that still answers 200.
3. `DisputeOutboxWorker` claims due events in one statement:
   `update … where id in (select id … for update skip locked) returning …`. Several instances can run at
   once, no two take the same event, and a worker that dies mid-event does not strand it.
4. Each attempt is counted. A failure records `last_error` and backs the event off exponentially
   (`15 · 2^attempts` seconds, capped at 5 minutes); after `dp.sync.max-attempts` (5) the event stops being
   claimed instead of spinning forever.
5. Submissions are additionally guarded by an `Idempotency-Key` (`submissions.idempotency_key` is unique).

**Signature verification.**

| Situation | Stored `verification` | Processed? |
|---|---|---|
| `PAYPAL_MODE=mock` (no signing key, no network) | `SKIPPED_SIMULATED` | yes |
| Event DisputePilot generated itself for a `DEMO-` dispute (`ingestSimulatedEvent`) | `SKIPPED_SIMULATED` | yes |
| Sandbox delivery that verifies | `VERIFIED` | yes |
| Sandbox delivery that does not verify | `FAILED` | **no** |

A delivery that arrives over the network and *claims* to be about a `DEMO-` dispute is still verified like any
other: a payload does not get to vouch for itself. PayPal's Webhooks simulator events cannot be verified by
postback, so they land as `FAILED` by design — use `POST /api/demo/disputes` for a demo instead.

**Retries** (`PayPalRetryPolicy`): at most 3 attempts, exponential backoff with full jitter, and only for 429,
5xx and connection errors. A GET is safe to repeat. A POST action is only repeated when PayPal's answer proves
it did not apply — a 429, or no response at all. A 5xx on a POST may mean "applied, but the answer was lost",
so it is reported rather than blindly repeated, and the dispute is re-fetched before anything else happens.

**Token handling**: the client-credentials token is cached and refreshed about 60 seconds before it expires.
Concurrent callers share one in-flight refresh. A 401 drops the cached token and re-sends the request once.

**Reconciliation** (`ReconciliationPoller`, every 10 minutes by default): lists disputes updated since the
cursor in `sync_state`, re-syncs each one, and advances the cursor to the newest `update_time` seen — but only
when every listed dispute synced, so a dispute that failed is picked up again next run rather than skipped.
A missed or rejected webhook therefore heals itself within one interval. `POST /api/sync` runs it on demand,
and `POST /api/disputes/{id}/sync` re-pulls a single dispute.

**Errors**: PayPal's error JSON (`name`, `message`, `debug_id`, `details[].issue`) is mapped onto
`PayPalApiException` and logged on every failure; `debug_id` is also persisted on the submission row. It is
the first thing PayPal support asks for.

## Configuration

| Variable | Meaning |
|---|---|
| `PAYPAL_MODE` | `mock` or `sandbox` |
| `PAYPAL_BASE_URL` | `https://api-m.sandbox.paypal.com`; any non-sandbox host refuses to start |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | sandbox REST app credentials |
| `PAYPAL_WEBHOOK_ID` | webhook ID from the developer dashboard; without it no delivery can be verified |
| `DP_SYNC_SCHEDULING` | `false` turns the outbox worker and poller off |
| `DP_OUTBOX_INTERVAL` | how often the outbox drains (default `PT5S`) |
| `DP_SYNC_POLL_INTERVAL` | reconciliation interval (default `PT10M`) |

## Shell helpers

`scripts/sandbox/` holds thin curl wrappers that read `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` from the
environment or a root `.env` (never committed). Every script refuses a non-sandbox host and prints the
`debug_id` of any error.

```bash
scripts/sandbox/token.sh                               # print an access token
scripts/sandbox/list.sh REQUIRED_ACTION 10             # list disputes
scripts/sandbox/get.sh <dispute_id>                    # one dispute, with its links[]
scripts/sandbox/require-evidence.sh <dispute_id>       # sandbox only: re-open for seller evidence
scripts/sandbox/adjudicate.sh <dispute_id> SELLER_FAVOR
scripts/sandbox/provide-evidence.sh <dispute_id> NLC4417002931 "notes" [file]
```

## Tests

No unit or integration test makes a network call: the real client is tested against WireMock
(`HttpPayPalDisputesClientTest`, `PayPalTokenProviderTest`, `RoutingPayPalDisputesClientTest`) and the webhook
and sync pipelines against a real Postgres (`WebhookPipelineTest`, `WebhookVerificationTest`,
`ReconciliationPollerTest`, `SyncEndpointsTest`).

`SandboxSmokeTest` is the one test that talks to PayPal, and it is skipped unless `PAYPAL_MODE=sandbox` with
credentials present:

```bash
cd backend && PAYPAL_MODE=sandbox ./mvnw -B test -Dtest=SandboxSmokeTest
```

It checks that client-credentials auth works, that a fetched dispute maps cleanly, and — as a negative case
against PayPal's error-simulation dispute ID `ERRDIS039` — that a failure arrives with PayPal's own `name`,
`message` and `debug_id`.

### Known limitation of the build container

The cloud container this project was developed in cannot complete a multipart upload to PayPal: a
`provide-evidence` request stalls and never returns, with `curl` exactly as with the Java client, while
auth, GETs and JSON POSTs all work. It looks like the container's egress proxy, not the code or PayPal. The
multipart body shape is therefore pinned by a WireMock test
(`provideEvidenceSendsMultipartWithAJsonInputPart`), and the real-sandbox multipart call is opt-in behind
`DP_SANDBOX_MULTIPART_SMOKE=true` so it can be run from a normal network.

### Verified against the real sandbox

Run on 3 November 2026 from this repository, with the sandbox credentials configured in the build
environment:

- `scripts/sandbox/token.sh` → access token issued.
- `scripts/sandbox/list.sh REQUIRED_ACTION 5` → `HTTP 200`, `{"items": []}` (this sandbox account has no
  disputes yet).
- `SandboxSmokeTest` with `PAYPAL_MODE=sandbox` → auth and list succeeded;
  `GET /v1/customer/disputes/ERRDIS039` and `POST …/ERRDIS039/require-evidence` both returned
  `404 RESOURCE_NOT_FOUND` with a `debug_id`, mapped onto `PayPalApiException` exactly as the error handling
  expects. (`ERRDIS039` is not provisioned for this account, so PayPal answers not-found rather than a
  simulated rate-limit; either way the mapping is what the test is about.)
