package com.disputepilot.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Every environment variable documented in CLAUDE.md, bound under the "dp" prefix (see application.yml). */
@ConfigurationProperties("dp")
public record AppProperties(
        @DefaultValue("") String adminToken,
        @DefaultValue("false") boolean devEndpoints,
        @DefaultValue Paypal paypal,
        @DefaultValue Llm llm,
        @DefaultValue Demo demo,
        @DefaultValue Eval eval) {

    public enum PaypalMode { MOCK, SANDBOX }

    public record Paypal(
            @DefaultValue("mock") PaypalMode mode,
            @DefaultValue("https://api-m.sandbox.paypal.com") String baseUrl,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String webhookId) {
    }

    public record Llm(
            @DefaultValue("") String mode,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String model,
            @DefaultValue("300") int dailyCap,
            @DefaultValue("openai-compatible") String provider,
            @DefaultValue("") String baseUrl,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("1000") long retryBaseDelayMs) {

        /** "live" or "recorded". Defaults to recorded when no API key is set. */
        public String effectiveMode() {
            if (mode != null && !mode.isBlank()) {
                return mode.trim().toLowerCase();
            }
            return apiKey != null && !apiKey.isBlank() ? "live" : "recorded";
        }

        /**
         * DP_LLM_MODEL, or the provider's default when it is blank: openai/gpt-oss-120b on an OpenAI-compatible
         * API (Groq), claude-haiku-5-5 on the Claude API.
         */
        public String effectiveModel() {
            if (model != null && !model.isBlank()) {
                return model.trim();
            }
            return "anthropic".equals(effectiveProvider()) ? "claude-haiku-5-5" : "openai/gpt-oss-120b";
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** "openai-compatible" (Groq and friends) or "anthropic". */
        public String effectiveProvider() {
            return provider == null || provider.isBlank() ? "openai-compatible" : provider.trim().toLowerCase();
        }
    }

    /**
     * @param rateLimit   live drafts per client IP per hour (DP_DEMO_RATE_LIMIT)
     * @param actionLimit "Simulate a dispute" / "Reset demo" calls per client IP per hour (DP_DEMO_ACTION_LIMIT)
     * @param seedOnStart load the starter disputes into an empty database at startup (DP_DEMO_SEED_ON_START)
     */
    public record Demo(
            @DefaultValue("6") int rateLimit,
            @DefaultValue("30") int actionLimit,
            @DefaultValue("false") boolean seedOnStart) {
    }

    public record Eval(@DefaultValue("fake") String mode) {
    }
}
