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
            @DefaultValue("claude-haiku-5-5") String model,
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

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** "openai-compatible" (Groq and friends) or "anthropic". */
        public String effectiveProvider() {
            return provider == null || provider.isBlank() ? "openai-compatible" : provider.trim().toLowerCase();
        }
    }

    public record Demo(@DefaultValue("6") int rateLimit) {
    }

    public record Eval(@DefaultValue("fake") String mode) {
    }
}
