package com.disputepilot.paypal;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retry rules from plan §8.3: at most {@code maxAttempts} attempts, exponential backoff with jitter, and only
 * for 429, 5xx and connection errors.
 *
 * <p>A non-idempotent call (every POST action on a dispute) is retried only when PayPal's answer proves the
 * request did not take effect - a 429 or no response at all. A 5xx may mean "applied but the answer was lost",
 * and plan §8.3 forbids blindly repeating such a POST; the caller re-fetches the dispute instead.
 */
public class PayPalRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(PayPalRetryPolicy.class);

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;

    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    public PayPalRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {
        this(maxAttempts, baseDelay, maxDelay, Thread::sleep);
    }

    PayPalRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleeper = sleeper;
    }

    /** The default policy: 3 attempts, 500ms base, 5s cap. */
    public static PayPalRetryPolicy defaults() {
        return new PayPalRetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public <T> T execute(String what, boolean idempotent, Supplier<T> call) {
        PayPalApiException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (PayPalApiException e) {
                boolean worthRetrying = idempotent ? e.retryable() : e.definitelyNotApplied();
                if (!worthRetrying || attempt == maxAttempts) {
                    throw e;
                }
                last = e;
                Duration delay = backoff(attempt);
                log.warn("PayPal {} failed (attempt {}/{}, status={}, name={}, debug_id={}); retrying in {}ms",
                        what, attempt, maxAttempts, e.httpStatus(), e.name(), e.debugId(), delay.toMillis());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last; // unreachable: the loop either returns or throws
    }

    /** Exponential backoff with full jitter, capped at maxDelay. */
    Duration backoff(int attempt) {
        long exp = baseDelay.toMillis() * (1L << (attempt - 1));
        long capped = Math.min(exp, maxDelay.toMillis());
        long jittered = capped <= 1 ? capped : ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        return Duration.ofMillis(jittered);
    }
}
