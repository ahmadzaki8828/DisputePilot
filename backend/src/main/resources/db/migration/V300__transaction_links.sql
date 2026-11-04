-- Session D: maps a real sandbox seller transaction to one of the fictional merchant's seeded orders, so a
-- real sandbox dispute can be answered with the demo store's records (POST /api/dev/link-transaction).
create table transaction_links (
  seller_transaction_id text primary key,
  order_id text not null,
  linked_by text not null default 'user',
  created_at timestamptz not null default now()
);

create index submissions_draft_id on submissions (draft_id);
