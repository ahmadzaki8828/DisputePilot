-- Session B (V200-V299): label every draft with the provider that produced it, and record whether the
-- grounding validator had to ask the agent for a repair.
alter table drafts add column provider text;
alter table drafts add column repair_attempted boolean not null default false;

comment on column drafts.provider is
  'Human-readable model provider behind this draft, e.g. "Groq (OpenAI-compatible API)" or "Recorded: ...".';
comment on column drafts.repair_attempted is
  'True when the first draft failed validation and the agent was asked to repair it.';

create index drafts_by_dispute on drafts (dispute_id, version desc);
