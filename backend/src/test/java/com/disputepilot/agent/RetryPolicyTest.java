package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void aShortRetryAfterIsHonoured() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        String result = new RetryPolicy(3, 10, sleeps::add, RetryPolicy.MAX_RETRY_WAIT).call(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetryPolicy.RateLimitedException("p", "slow down", Duration.ofSeconds(5));
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).containsExactly(5000L);
    }

    @Test
    void aRetryAfterOverTheCapFailsAtOnceWithoutSleeping() {
        List<Long> sleeps = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var policy = new RetryPolicy(4, 10, sleeps::add, RetryPolicy.MAX_RETRY_WAIT);
        assertThatThrownBy(() -> policy.call(() -> {
            calls.incrementAndGet();
            throw new RetryPolicy.RateLimitedException("p", "daily quota", Duration.ofMinutes(20));
        })).isInstanceOfSatisfying(RetryPolicy.RateLimitedException.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMinutes(20)));
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }
}
