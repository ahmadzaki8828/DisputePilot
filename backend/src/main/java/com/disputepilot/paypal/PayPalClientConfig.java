package com.disputepilot.paypal;

import com.disputepilot.common.AppProperties;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the PayPal clients. {@code PAYPAL_MODE=mock} uses the simulated client alone (no network at all);
 * {@code PAYPAL_MODE=sandbox} uses {@link RoutingPayPalDisputesClient}, which keeps simulated {@code DEMO-}
 * disputes working alongside the real sandbox (plan §8.1). There is no live mode: {@code SandboxGuard}
 * refuses to start against any non-sandbox host.
 */
@Configuration
public class PayPalClientConfig {

    private static final Logger log = LoggerFactory.getLogger(PayPalClientConfig.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

    /** Bounded-timeout RestClient pointed at the sandbox base URL. */
    @Bean
    RestClient payPalRestClient(AppProperties props) {
        return restClient(props.paypal().baseUrl(), CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    /**
     * Builds the HTTP client used for every PayPal call. HTTP/1.1 is pinned (PayPal supports both, and the
     * JDK client's h2c upgrade adds nothing here), the system proxy settings are honoured so the app works
     * behind an egress proxy, and both timeouts are bounded so a hung PayPal call cannot hold a thread.
     */
    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(connectTimeout, readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(b -> b.version(HttpClient.Version.HTTP_1_1))
                        .withProxySelector(ProxySelector.getDefault())
                        .build(settings))
                .build();
    }

    @Bean
    PayPalRetryPolicy payPalRetryPolicy() {
        return PayPalRetryPolicy.defaults();
    }

    @Bean
    PayPalTokenProvider payPalTokenProvider(RestClient payPalRestClient, AppProperties props, JsonMapper json,
            PayPalRetryPolicy retry) {
        return new PayPalTokenProvider(payPalRestClient, props.paypal().clientId(), props.paypal().clientSecret(),
                json, retry);
    }

    @Bean
    HttpPayPalDisputesClient httpPayPalDisputesClient(RestClient payPalRestClient, PayPalTokenProvider tokens,
            JsonMapper json, PayPalDisputeMapper mapper, PayPalRetryPolicy retry, AppProperties props) {
        return new HttpPayPalDisputesClient(payPalRestClient, tokens, json, mapper, retry,
                props.paypal().webhookId());
    }

    /** The client the rest of the app injects. */
    @Bean
    @Primary
    PayPalDisputesClient payPalDisputesClient(AppProperties props, SimulatedPayPalDisputesClient simulated,
            HttpPayPalDisputesClient http) {
        if (props.paypal().mode() == AppProperties.PaypalMode.MOCK) {
            log.info("PAYPAL_MODE=mock: simulated PayPal only, no network calls");
            return simulated;
        }
        log.info("PAYPAL_MODE=sandbox: routing DEMO- disputes to the simulated client and the rest to {}",
                props.paypal().baseUrl());
        return new RoutingPayPalDisputesClient(simulated, http);
    }
}
