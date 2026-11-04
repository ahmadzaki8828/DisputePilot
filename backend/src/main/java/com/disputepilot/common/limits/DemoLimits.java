package com.disputepilot.common.limits;

import com.disputepilot.common.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-client-IP limits for the public demo (plan §9.2 step 7): live drafts per hour ({@code DP_DEMO_RATE_LIMIT})
 * and simulate/reset calls per hour ({@code DP_DEMO_ACTION_LIMIT}). In memory: a restart forgives everyone,
 * which is fine for a demo guard.
 */
@Component
public class DemoLimits {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int draftsPerHour;
    private final int actionsPerHour;
    private final ConcurrentHashMap<String, Bucket> drafts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> actions = new ConcurrentHashMap<>();

    public DemoLimits(AppProperties props) {
        this.draftsPerHour = Math.max(1, props.demo().rateLimit());
        this.actionsPerHour = Math.max(1, props.demo().actionLimit());
    }

    public int draftsPerHour() {
        return draftsPerHour;
    }

    /** Takes one live draft from this client's hourly allowance; false when it is used up. */
    public boolean tryDraft(String client) {
        return take(drafts, client, draftsPerHour);
    }

    /** Takes one simulate/reset call from this client's hourly allowance; false when it is used up. */
    public boolean tryAction(String client) {
        return take(actions, client, actionsPerHour);
    }

    private static boolean take(ConcurrentHashMap<String, Bucket> buckets, String client, int perHour) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }
        Bucket bucket = buckets.computeIfAbsent(client == null ? "unknown" : client, k -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perHour).refillIntervally(perHour, Duration.ofHours(1)).build())
                .build());
        return bucket.tryConsume(1);
    }
}
