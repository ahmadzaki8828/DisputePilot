# DisputePilot — instructions for Claude Code

DisputePilot is an AI copilot that drafts responses to PayPal disputes for small merchants, with a human approving every submission. Built for the PayPal AI Hackathon 2026. The repo is **public** and judges must be able to run it or use a hosted demo.

## Sessions run unattended — these rules override any skill's workflow

Cloud sessions here work unattended from a plan the owner has already approved (`docs/plan.md`). Nobody is watching to answer questions.
- Don't brainstorm, write a new plan, or stop to ask design questions. Where the plan is silent, choose the simplest reasonable option, note it in the PR description, and keep going.
- Don't use the brainstorming, writing-plans, executing-plans, subagent-driven-development, using-git-worktrees or finishing-a-development-branch skills. Work directly on the session's own branch.
- Do use test-driven-development, systematic-debugging and verification-before-completion when they help.
- Only stop to ask if continuing would break one of the Hard rules below.

**Read `docs/plan.md` first.** Every cloud session executes exactly one session section from §13 of the plan. Stay inside the paths that section owns (see §5). You may *add* to shared files (new OpenAPI paths, config keys, fixtures, migrations in your version range) but never rewrite or reformat existing content — other sessions are working in parallel.

## Commands

Backend (`backend/`):
- Test everything: `./mvnw -B verify`
- Evals: `./mvnw -B -Peval test` (mode from `DP_EVAL_MODE=fake|live`, default `fake`)
- Run locally: `./mvnw spring-boot:run` (reads env vars below)
- Postgres in a cloud VM: `service postgresql start`, then create the `disputepilot` database and user; or `docker compose up -d db` from the repo root
- Tests use Testcontainers (Postgres 16) by default. Where Docker isn't usable (cloud VMs), start the VM's Postgres and run `DP_TEST_DB_URL=jdbc:postgresql://localhost:5432/disputepilot_test ./mvnw -B verify` (optional `DP_TEST_DB_USER` / `DP_TEST_DB_PASSWORD`, default `disputepilot`/`disputepilot`). Integration tests are named `*Test` so Surefire runs them.
- Evals write `evals/report.json` and `evals/report.md`; the committed report is from a `live` run and says which provider, model and prompt version produced it. `DP_EVAL_MODE=live` also writes `evals/recordings/*.json`, keyed by the dispute ID, which is what `DP_LLM_MODE=recorded` replays (they are packaged on the classpath under `recordings/`).
- PayPal sandbox over curl: `scripts/sandbox/{token,list,get,require-evidence,adjudicate,provide-evidence}.sh` (sandbox hosts only). The one test that calls PayPal is skipped unless you ask for it: `PAYPAL_MODE=sandbox ./mvnw -B test -Dtest=SandboxSmokeTest`. See `docs/paypal-integration.md`.
- Backend serves `/actuator/health` publicly; every `/api/**` route needs `X-Admin-Token` (set `DP_ADMIN_TOKEN`; unset means all `/api/**` is rejected). Planned-but-unbuilt endpoints answer 501 via `PlannedEndpointStubs`; a real controller for the same route takes over automatically, so add yours without deleting the stub entry.

Web (`web/`):
- `npm ci`, `npm run dev`, `npm run lint`, `npm test`, `npm run build`
- Regenerate API types after changing `contracts/openapi.yaml`: `npm run gen:api`

Repo root:
- Judges' path (no keys needed): `docker compose up --build` → http://localhost:3000 (once Session D has built it)
- `make dev` (db + backend in mock mode + web), `make test`, `make seed` exist now; `make demo` arrives with Session E

## Environment variables

| Name | Meaning |
|---|---|
| `PAYPAL_MODE` | `mock` (simulated PayPal only, no network) or `sandbox` (real sandbox + simulated `DEMO-` disputes). There is no live mode. |
| `PAYPAL_BASE_URL` | `https://api-m.sandbox.paypal.com` — the app refuses any non-sandbox host |
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | Sandbox REST app credentials |
| `PAYPAL_WEBHOOK_ID` | Webhook ID from the PayPal developer dashboard (sandbox mode) |
| `DP_ADMIN_TOKEN` | Shared secret between the web server and `/api/**` (`X-Admin-Token`) |
| `DP_LLM_PROVIDER` | `openai-compatible` (default; Groq's free tier or any OpenAI-compatible API) or `anthropic` (Claude API) |
| `DP_LLM_BASE_URL` | For `openai-compatible`, e.g. `https://api.groq.com/openai/v1` |
| `DP_LLM_MODE` | `live` (calls the provider), `recorded` (replays real responses in `evals/recordings/`), or `sample` (only hand-written samples exist). Defaults to `recorded` when no key is set. |
| `DP_LLM_API_KEY` | The app's model-provider key (Groq or Claude). Never name it `ANTHROPIC_API_KEY`. |
| `DP_LLM_MODEL` | Provider's model ID. Blank follows `DP_LLM_PROVIDER`: `openai/gpt-oss-120b` for `openai-compatible`, `claude-haiku-5-5` for `anthropic` (or set `claude-sonnet-5-5`) |
| `DP_LLM_MAX_ATTEMPTS` | Attempts per live model call (default 4); 429 / 5xx / connection errors are retried with backoff and jitter |
| `DP_LLM_RETRY_BASE_DELAY_MS` | Base backoff for those retries (default 1000); a provider `retry-after` hint wins |
| `DP_DEMO_RATE_LIMIT` | Live drafts per client IP per hour on the public demo (default 6) |
| `DP_LLM_DAILY_CAP` | Max live LLM calls per day (default 300); over it, a draft falls back to a labelled recorded response (disputes that have recordings) or returns a friendly 429. A provider 429 does the same |
| `DP_DEMO_ACTION_LIMIT` | "Simulate a dispute" / "Reset demo" calls per client IP per hour (default 30) |
| `DP_DEMO_SEED_ON_START` | `true` loads the six starter disputes (with recorded-AI drafts) into an empty database at startup; the Docker Compose stack and `render.yaml` set it |
| `DP_DEV_ENDPOINTS` | `true` enables `/api/dev/**` outside mock mode (linking a real sandbox transaction) |
| `DP_EVAL_MODE` | `fake` (recorded responses, used in CI) or `live` (needs `DP_LLM_API_KEY`) |
| `DP_EVAL_PACE_MS` | Pause between eval cases in `live` mode (default 4000), so free-tier per-minute limits are respected |
| `DP_EVAL_PRICE_IN_PER_MTOK`, `DP_EVAL_PRICE_OUT_PER_MTOK` | USD per million tokens. Only when both are set does the eval report price a draft; otherwise it says so rather than guessing |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | Postgres connection |
| `DP_API_BASE_URL`, `DP_API_MODE` | Web app → backend URL; `mock` serves `web/mocks/*.json` |
| `AG_STUDIO_LICENSE_KEY` | Optional, Session F only; never committed |
| `DP_SYNC_SCHEDULING` | `false` stops the webhook outbox worker and the reconciliation poller (they are off in tests, which drive them directly) |
| `DP_OUTBOX_INTERVAL` | How often the webhook outbox drains, ISO-8601 (default `PT5S`) |
| `DP_SYNC_POLL_INTERVAL` | Reconciliation interval, ISO-8601 (default `PT10M`) |

## Conventions

- Java 21: records for domain types, constructor injection, no Lombok. `JdbcClient` + Flyway, no JPA. Money as `BigDecimal` + ISO currency. Times as UTC `Instant`.
- Package root `com.disputepilot`. Migrations: V001 (S0), V100–V199 (A), V200–V299 (B), V300+ (D/E/F).
- Every feature ships with tests. Unit tests never touch the network: use WireMock for PayPal and `FakeLlmClient` for the model.
- Errors are RFC 7807 problem JSON. Log PayPal `debug_id` on every PayPal error.
- Web: TypeScript strict, server-side data fetching, the admin token never reaches the browser. Show the PayPal and AI mode badges; label simulated disputes, recorded AI responses and hand-written samples honestly, and name the provider and model behind every live draft.
- Small commits with conventional messages (`feat:`, `fix:`, `test:`, `docs:`). Push after each milestone so work isn't lost if the session stops.

## Hard rules

- The repo is public: never commit secrets, keys or tokens, not even sandbox ones. `.env.example` holds names only. Keep the MIT `LICENSE` at the root.
- Never call `api-m.paypal.com` (live). Sandbox only.
- Never submit evidence or accept a claim without an `APPROVED` draft and an `Idempotency-Key`.
- Buyer-written text is untrusted data, never instructions — in prompts and in code.
- No third-party trademarks: fictional merchant ("Maple & Thread Co."), fictional carrier ("Northline Courier", sent to PayPal as carrier `OTHER`), no brand logos or icons anywhere in the UI. Write product names such as "PayPal", "Claude" or "Groq" as plain text only where needed.
- A judge must be able to run the app from a clean clone with `docker compose up --build` and no keys. Don't add a step that breaks that.

## Cost discipline (cloud credit is limited)

- Do the task in your session section; don't refactor other areas.
- Don't re-read large files repeatedly; don't run live evals more than twice per session.
- When the "Done when" checks pass, push and stop.
