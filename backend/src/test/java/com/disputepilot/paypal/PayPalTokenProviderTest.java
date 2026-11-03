package com.disputepilot.paypal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** OAuth token caching and refresh (plan §8.2). */
class PayPalTokenProviderTest {

    private WireMockServer server;

    @BeforeEach
    void setUp() {
        server = PayPalWireMockSupport.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void fetchesOnceAndCachesALongLivedToken() {
        PayPalWireMockSupport.stubToken(server, "token-1", 3600);
        PayPalTokenProvider tokens = PayPalWireMockSupport.tokens(server);

        assertThat(tokens.accessToken()).isEqualTo("token-1");
        assertThat(tokens.accessToken()).isEqualTo("token-1");
        assertThat(tokens.accessToken()).isEqualTo("token-1");

        server.verify(1, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .withHeader("Authorization", equalTo("Basic " + Base64.getEncoder().encodeToString(
                        "demo-client-id:demo-client-secret".getBytes(StandardCharsets.UTF_8))))
                .withRequestBody(equalTo("grant_type=client_credentials")));
    }

    @Test
    void refreshesAboutAMinuteBeforeTheTokenExpires() {
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .inScenario("refresh").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"token-old\",\"expires_in\":3600}"))
                .willSetStateTo("second"));
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .inScenario("refresh").whenScenarioStateIs("second")
                .willReturn(okJson("{\"access_token\":\"token-new\",\"expires_in\":3600}")));

        MutableClock clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
        PayPalTokenProvider tokens = new PayPalTokenProvider(PayPalWireMockSupport.restClient(server),
                "demo-client-id", "demo-client-secret", PayPalWireMockSupport.JSON,
                PayPalWireMockSupport.fastRetry(), clock);

        assertThat(tokens.accessToken()).isEqualTo("token-old");

        // Still inside the token's life, and outside the 60s refresh margin: no new call.
        clock.advance(Duration.ofSeconds(3600).minus(PayPalTokenProvider.REFRESH_MARGIN).minusSeconds(5));
        assertThat(tokens.accessToken()).isEqualTo("token-old");
        server.verify(1, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));

        // Inside the margin: refreshed before PayPal would start rejecting the old token.
        clock.advance(Duration.ofSeconds(10));
        assertThat(tokens.accessToken()).isEqualTo("token-new");
        assertThat(tokens.accessToken()).isEqualTo("token-new");
        server.verify(2, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void aShortLivedTokenStillGetsSomeUsefulLife() {
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .willReturn(okJson("{\"access_token\":\"brief\",\"expires_in\":30}")));
        MutableClock clock = new MutableClock(Instant.parse("2026-11-03T12:00:00Z"));
        PayPalTokenProvider tokens = new PayPalTokenProvider(PayPalWireMockSupport.restClient(server),
                "demo-client-id", "demo-client-secret", PayPalWireMockSupport.JSON,
                PayPalWireMockSupport.fastRetry(), clock);

        // The 60s margin would make a 30s token useless, so it falls back to half the lifetime.
        assertThat(tokens.accessToken()).isEqualTo("brief");
        clock.advance(Duration.ofSeconds(10));
        assertThat(tokens.accessToken()).isEqualTo("brief");
        server.verify(1, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));

        clock.advance(Duration.ofSeconds(10));
        tokens.accessToken();
        server.verify(2, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    /** A clock the test moves by hand, so token expiry needs no real waiting. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void invalidateForcesTheNextCallToReauthenticate() {
        PayPalWireMockSupport.stubToken(server, "token-1", 3600);
        PayPalTokenProvider tokens = PayPalWireMockSupport.tokens(server);

        tokens.accessToken();
        tokens.invalidate();
        tokens.accessToken();

        server.verify(2, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void concurrentCallersShareASingleTokenRequest() throws Exception {
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .willReturn(okJson("{\"access_token\":\"token-1\",\"expires_in\":3600}").withFixedDelay(150)));
        PayPalTokenProvider tokens = PayPalWireMockSupport.tokens(server);

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Callable<String>> calls = IntStream.range(0, 8)
                    .<Callable<String>>mapToObj(i -> tokens::accessToken).toList();
            for (Future<String> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isEqualTo("token-1");
            }
        }
        server.verify(1, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void missingCredentialsFailFastWithoutCallingPayPal() {
        PayPalTokenProvider tokens = new PayPalTokenProvider(PayPalWireMockSupport.restClient(server), "", "",
                PayPalWireMockSupport.JSON, PayPalWireMockSupport.fastRetry());

        assertThatThrownBy(tokens::accessToken)
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.name()).isEqualTo("MISSING_CREDENTIALS");
                    assertThat(e.httpStatus()).isEqualTo(401);
                });
        server.verify(0, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void oauthErrorIsMappedToAPayPalApiException() {
        PayPalWireMockSupport.stubTokenFailure(server, 401);
        PayPalTokenProvider tokens = PayPalWireMockSupport.tokens(server);

        assertThatThrownBy(tokens::accessToken)
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(e.name()).isEqualTo("invalid_client");
                    assertThat(e.getMessage()).contains("Client Authentication failed");
                });
        // 401 is not retryable, so exactly one attempt was made.
        server.verify(1, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }

    @Test
    void tokenFetchRetriesServerErrors() {
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("ok"));
        server.stubFor(post(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH))
                .inScenario("flaky").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"access_token\":\"token-1\",\"expires_in\":3600}")));

        assertThat(PayPalWireMockSupport.tokens(server).accessToken()).isEqualTo("token-1");
        server.verify(2, postRequestedFor(urlPathEqualTo(PayPalWireMockSupport.TOKEN_PATH)));
    }
}
