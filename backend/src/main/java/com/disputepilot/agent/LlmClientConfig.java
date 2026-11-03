package com.disputepilot.agent;

import com.disputepilot.common.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chooses the {@link LlmClient} from {@code DP_LLM_MODE} and {@code DP_LLM_PROVIDER} (plan §9.1).
 *
 * <ul>
 *   <li>{@code live} with a key: the provider is called - {@code openai-compatible} (Groq and friends) or
 *       {@code anthropic} (Claude API).</li>
 *   <li>{@code recorded} / {@code sample}, or no key at all: recorded responses from {@code evals/recordings}
 *       are replayed, so a judge can run the whole app with no accounts.</li>
 * </ul>
 */
@Configuration
public class LlmClientConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmClientConfig.class);

    @Bean
    LlmClient llmClient(AppProperties props, JsonMapper mapper) {
        return create(props.llm(), mapper);
    }

    /** Also used by the eval runner, which builds a client without a Spring context. */
    public static LlmClient create(AppProperties.Llm llm, JsonMapper mapper) {
        String mode = llm.effectiveMode();
        if (!"live".equals(mode)) {
            log.info("LLM mode '{}': replaying recorded responses", mode);
            return new RecordedLlmClient();
        }
        if (!llm.hasApiKey()) {
            log.warn("DP_LLM_MODE=live but DP_LLM_API_KEY is empty - falling back to recorded responses");
            return new RecordedLlmClient();
        }
        RetryPolicy retry = new RetryPolicy(llm.maxAttempts(), llm.retryBaseDelayMs());
        LlmClient client = switch (llm.effectiveProvider()) {
            case "anthropic" -> new AnthropicLlmClient(llm.apiKey(), blankToNull(llm.baseUrl()), llm.model(),
                    mapper, retry);
            case "openai-compatible" -> new OpenAiCompatibleLlmClient(llm.baseUrl(), llm.apiKey(), llm.model(),
                    mapper, retry);
            default -> throw new IllegalStateException("Unknown DP_LLM_PROVIDER '" + llm.provider()
                    + "'; expected 'openai-compatible' or 'anthropic'");
        };
        log.info("LLM mode live: provider {}, model {}", client.providerName(), llm.model());
        return client;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
