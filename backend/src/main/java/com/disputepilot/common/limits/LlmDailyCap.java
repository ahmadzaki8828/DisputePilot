package com.disputepilot.common.limits;

import com.disputepilot.common.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Caps live LLM calls per UTC day ({@code DP_LLM_DAILY_CAP}); past it, drafting falls back to recordings. */
@Component
public class LlmDailyCap {

    private final int cap;
    private final Clock clock;
    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate day;

    @Autowired
    public LlmDailyCap(AppProperties props) {
        this(props.llm().dailyCap(), Clock.systemUTC());
    }

    public LlmDailyCap(int cap, Clock clock) {
        this.cap = cap;
        this.clock = clock;
        this.day = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** Takes one call from today's allowance; false when it is used up. */
    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            used.set(0);
        }
        if (used.get() >= cap) {
            return false;
        }
        used.incrementAndGet();
        return true;
    }

    public int used() {
        return used.get();
    }

    public int cap() {
        return cap;
    }
}
