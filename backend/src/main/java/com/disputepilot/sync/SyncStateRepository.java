package com.disputepilot.sync;

import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@code sync_state} key/value table; currently holds the reconciliation cursor. */
@Repository
public class SyncStateRepository {

    /** Highest PayPal {@code update_time} already reconciled. */
    public static final String DISPUTES_CURSOR = "paypal.disputes.cursor";

    private final JdbcClient jdbc;

    public SyncStateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.sql("select value from sync_state where key = :key").param("key", key)
                .query(String.class).optional();
    }

    public void put(String key, String value) {
        jdbc.sql("""
                insert into sync_state (key, value) values (:key, :value)
                on conflict (key) do update set value = excluded.value, updated_at = now()
                """)
                .param("key", key).param("value", value).update();
    }

    public Optional<Instant> cursor() {
        return get(DISPUTES_CURSOR).map(Instant::parse);
    }

    /** Move the cursor forward only; a late page must never rewind it. */
    public void advanceCursor(Instant to) {
        if (to == null) {
            return;
        }
        Instant current = cursor().orElse(null);
        if (current == null || to.isAfter(current)) {
            put(DISPUTES_CURSOR, to.toString());
        }
    }
}
