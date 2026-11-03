package com.disputepilot.common.limits;

import com.disputepilot.agent.LlmClient;
import com.disputepilot.agent.LlmException;
import com.disputepilot.agent.LlmRequest;
import com.disputepilot.agent.LlmResult;

/**
 * Wraps a live model client with the daily cap. Over the cap it fails like a rate-limited provider (HTTP 429), so
 * the draft service handles "the provider is busy" and "we are over budget" the same way: fall back to a labelled
 * recorded response, or answer with a friendly 429.
 */
public class CappedLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final LlmDailyCap cap;

    public CappedLlmClient(LlmClient delegate, LlmDailyCap cap) {
        this.delegate = delegate;
        this.cap = cap;
    }

    @Override
    public LlmResult run(LlmRequest request) {
        if (!cap.tryAcquire()) {
            throw new LlmException(delegate.providerName(), 429,
                    "The public demo's daily limit of " + cap.cap() + " live AI calls has been reached.");
        }
        return delegate.run(request);
    }

    @Override
    public boolean isLive() {
        return delegate.isLive();
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }
}
