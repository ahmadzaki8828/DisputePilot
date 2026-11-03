create table disputes (
  id uuid primary key,
  paypal_dispute_id text not null unique,   -- real sandbox ID, or DEMO-… for simulated
  simulated boolean not null default false,
  reason text not null,                     -- e.g. MERCHANDISE_OR_SERVICE_NOT_RECEIVED
  status text not null,                     -- e.g. WAITING_FOR_SELLER_RESPONSE, UNDER_REVIEW, RESOLVED
  life_cycle_stage text,                    -- INQUIRY, CHARGEBACK, PRE_ARBITRATION, ...
  amount_value numeric(19,2) not null,
  amount_currency char(3) not null,
  seller_response_due_at timestamptz,
  disputed_transaction_id text,             -- seller transaction id, used to find the order
  requested_evidence jsonb not null default '[]', -- evidence types PayPal requested from seller
  allowed_actions text[] not null default '{}',   -- derived from links[].rel
  outcome text,                             -- e.g. RESOLVED_SELLER_FAVOUR when known
  raw jsonb not null,                       -- last full dispute-details payload
  paypal_update_time timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table webhook_events (
  id uuid primary key,
  paypal_event_id text not null unique,     -- idempotency for deliveries
  event_type text not null,
  resource_id text,
  payload jsonb not null,
  verification text not null,               -- VERIFIED | SKIPPED_SIMULATED | FAILED
  received_at timestamptz not null default now(),
  processed_at timestamptz,
  attempts int not null default 0,
  last_error text
);
create index webhook_events_unprocessed on webhook_events (received_at) where processed_at is null;

create table drafts (
  id uuid primary key,
  dispute_id uuid not null references disputes(id),
  version int not null,
  status text not null,                     -- DRAFT | NEEDS_REVIEW | APPROVED | SUBMITTED | REJECTED | SUPERSEDED
  recommendation text not null,             -- FIGHT | ACCEPT | OFFER | NEEDS_INFO
  confidence numeric(3,2) not null,
  rationale text not null,
  evidence jsonb not null,                  -- PayPal-shaped evidences[] we would submit
  notes text not null,                      -- text sent to PayPal
  citations jsonb not null,                 -- [{id, kind, label}]
  missing_info jsonb not null default '[]',
  validation jsonb not null,                -- {passed, issues[]}
  llm_mode text not null,                   -- live | recorded
  model text, prompt_version text,
  input_tokens int, output_tokens int, latency_ms int,
  edited_by_human boolean not null default false,
  created_at timestamptz not null default now(),
  unique (dispute_id, version)
);

create table submissions (
  id uuid primary key,
  draft_id uuid not null references drafts(id),
  idempotency_key text not null unique,
  action text not null,                     -- PROVIDE_EVIDENCE | ACCEPT_CLAIM | MAKE_OFFER
  request_payload jsonb not null,
  paypal_response jsonb,
  paypal_debug_id text,
  status text not null,                     -- PENDING | SUCCEEDED | FAILED
  submitted_by text not null,
  created_at timestamptz not null default now()
);

create table audit_log (
  id bigserial primary key,
  at timestamptz not null default now(),
  actor text not null,                      -- system | agent | user
  action text not null,                     -- WEBHOOK_RECEIVED, DISPUTE_SYNCED, DRAFT_CREATED, ...
  dispute_id uuid,
  details jsonb not null default '{}'
);

create table sync_state (
  key text primary key,
  value text not null,
  updated_at timestamptz not null default now()
);
