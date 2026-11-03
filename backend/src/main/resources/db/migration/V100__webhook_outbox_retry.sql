-- Session A: backoff bookkeeping for the webhook outbox worker, plus lookup indexes
-- used by the reconciliation poller. Additive only; V001 is left untouched.

alter table webhook_events add column next_attempt_at timestamptz;

create index webhook_events_retry_due on webhook_events (next_attempt_at)
    where processed_at is null;

create index disputes_paypal_update_time on disputes (paypal_update_time desc nulls last);
