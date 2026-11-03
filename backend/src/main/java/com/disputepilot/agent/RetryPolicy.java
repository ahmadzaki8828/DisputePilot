package com.disputepilot.agent;

import java.time.Duration;
import java.util.Random;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exponential backoff with jitter for model-provider calls. Groq's free tier returns 429 with a
 * Retry-After hint well before the daily cap is reached, so every live call goes through this.
 */
public final class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final long baseDelayMs;
    private final Random random = new Random();
    private final Sleeper sleeper;

    /** Pulled out so tests do not actually wait. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public RetryPolicy(int maxAttempts, long baseDelayMs) {
        this(maxAttempts, baseDelayMs, Thread::sleep);
    }

    public RetryPolicy(int maxAttempts, long baseDelayMs, Sleeper sleeper) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = Math.max(0, baseDelayMs);
        this.sleeper = sleeper;
    }

    /** Runs the call, retrying retryable {@link LlmException}s. Honours a Retry-After hint when present. */
    public <T> T call(Supplier<T> body) {
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return body.get();
            } catch (LlmException e) {
                last = e;
                if (!e.retryable() || attempt == maxAttempts) {
                    throw e;
                }
                long wait = e instanceof RateLimitedException r && r.retryAfter() != null
                        ? r.retryAfter().toMillis()
                        : backoffMs(attempt);
                log.warn("{} call failed (attempt {}/{}, status {}): {} - retrying in {} ms",
                        e.provider(), attempt, maxAttempts, e.httpStatus(), e.getMessage(), wait);
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    long backoffMs(int attempt) {
        long exponential = baseDelayMs * (1L << (attempt - 1));
        long jitter = baseDelayMs == 0 ? 0 : random.nextLong(baseDelayMs);
        return exponential + jitter;
    }

    /** 429 with the provider's Retry-After hint, when it sent one. */
    public static class RateLimitedException extends LlmException {
        private final Duration retryAfter;

        public RateLimitedException(String provider, String message, Duration retryAfter) {
            super(provider, 429, message);
            this.retryAfter = retryAfter;
        }

        public Duration retryAfter() {
            return retryAfter;
        }
    }
}
