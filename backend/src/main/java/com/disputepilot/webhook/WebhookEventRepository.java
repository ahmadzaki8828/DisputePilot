package com.disputepilot.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code webhook_events}: store-then-process, keyed by PayPal's event ID so deliveries are idempotent. */
@Repository
public class WebhookEventRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public WebhookEventRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Store one delivery. PayPal may deliver the same event more than once, so a repeat of a
     * {@code paypal_event_id} we already hold is a no-op.
     *
     * @return the stored row's id, or empty when this event was already stored
     */
    public Optional<UUID> store(String paypalEventId, String eventType, String resourceId, String verification,
            JsonNode payload) {
        return jdbc.sql("""
                insert into webhook_events (id, paypal_event_id, event_type, resource_id, payload, verification)
                values (:id, :eventId, :eventType, :resourceId, cast(:payload as jsonb), :verification)
                on conflict (paypal_event_id) do nothing
                returning id
                """)
                .param("id", UUID.randomUUID())
                .param("eventId", paypalEventId)
                .param("eventType", eventType)
                .param("resourceId", resourceId)
                .param("verification", verification)
                .param("payload", json.writeValueAsString(payload))
                .query(UUID.class)
                .optional();
    }

    /**
     * Claim up to {@code batchSize} events that are due for processing, skipping rows another worker holds.
     *
     * <p>The claim is a single statement: the {@code select ... for update skip locked} picks the rows and the
     * surrounding update stamps the attempt and the next retry time, so two workers can never take the same
     * event and a worker that dies mid-processing does not leave the event claimed forever. Events whose
     * signature failed verification are never processed.
     */
    public List<WebhookEvent> claimBatch(int batchSize, int maxAttempts) {
        return jdbc.sql("""
                update webhook_events set
                    attempts = attempts + 1,
                    next_attempt_at = now() + make_interval(
                        secs => least(300, 15 * power(2, attempts))::double precision)
                where id in (
                    select id from webhook_events
                    where processed_at is null
                      and attempts < :maxAttempts
                      and verification in ('VERIFIED', 'SKIPPED_SIMULATED')
                      and (next_attempt_at is null or next_attempt_at <= now())
                    order by received_at
                    limit :batchSize
                    for update skip locked)
                returning id, paypal_event_id, event_type, resource_id, verification, received_at, attempts
                """)
                .param("batchSize", batchSize)
                .param("maxAttempts", maxAttempts)
                .query((rs, i) -> new WebhookEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("paypal_event_id"),
                        rs.getString("event_type"),
                        rs.getString("resource_id"),
                        rs.getString("verification"),
                        rs.getTimestamp("received_at").toInstant(),
                        rs.getInt("attempts")))
                .list();
    }

    public void markProcessed(UUID id) {
        jdbc.sql("update webhook_events set processed_at = now(), last_error = null where id = :id")
                .param("id", id).update();
    }

    /** Record why an attempt failed. {@code attempts} was already incremented when the event was claimed. */
    public void markFailed(UUID id, String error) {
        jdbc.sql("update webhook_events set last_error = :error where id = :id")
                .param("id", id)
                .param("error", error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 2000)))
                .update();
    }

    public Optional<JsonNode> payload(UUID id) {
        return jdbc.sql("select payload::text from webhook_events where id = :id").param("id", id)
                .query(String.class).optional().map(json::readTree);
    }

    public int countByEventId(String paypalEventId) {
        return jdbc.sql("select count(*) from webhook_events where paypal_event_id = :eventId")
                .param("eventId", paypalEventId).query(Integer.class).single();
    }

    /** Events still waiting to be processed, including ones not yet due for a retry. */
    public int countUnprocessed() {
        return jdbc.sql("select count(*) from webhook_events where processed_at is null")
                .query(Integer.class).single();
    }

    /** The processing bookkeeping for one event, for the dashboard and for tests. */
    public record State(boolean processed, int attempts, String lastError, Instant nextAttemptAt) {
    }

    public Optional<State> state(UUID id) {
        return jdbc.sql("""
                select processed_at, attempts, last_error, next_attempt_at from webhook_events where id = :id
                """)
                .param("id", id)
                .query((rs, i) -> {
                    java.sql.Timestamp next = rs.getTimestamp("next_attempt_at");
                    return new State(rs.getTimestamp("processed_at") != null, rs.getInt("attempts"),
                            rs.getString("last_error"), next == null ? null : next.toInstant());
                })
                .optional();
    }

    /** Clear the retry delay so a test (or an operator) can process an event immediately. */
    public void clearRetryDelay(UUID id) {
        jdbc.sql("update webhook_events set next_attempt_at = null where id = :id").param("id", id).update();
    }
}
