package com.disputepilot.paypal;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth2 client-credentials token for the PayPal sandbox ({@code POST /v1/oauth2/token}, Basic
 * {@code client_id:secret}). The token is cached and refreshed shortly before it expires; access is
 * thread-safe, and concurrent callers that arrive during a refresh wait for the single in-flight refresh.
 */
public class PayPalTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(PayPalTokenProvider.class);
    /** Refresh this long before the token actually expires (plan §8.2: "~60s before expiry"). */
    static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestClient rest;
    private final String clientId;
    private final String clientSecret;
    private final JsonMapper json;
    private final PayPalRetryPolicy retry;
    private final Clock clock;

    private volatile CachedToken cached;

    record CachedToken(String value, Instant refreshAt) {
        boolean usableAt(Instant now) {
            return now.isBefore(refreshAt);
        }
    }

    public PayPalTokenProvider(RestClient rest, String clientId, String clientSecret, JsonMapper json,
            PayPalRetryPolicy retry) {
        this(rest, clientId, clientSecret, json, retry, Clock.systemUTC());
    }

    /** Test seam: the clock that decides when the cached token is due for refresh. */
    PayPalTokenProvider(RestClient rest, String clientId, String clientSecret, JsonMapper json,
            PayPalRetryPolicy retry, Clock clock) {
        this.rest = rest;
        this.clientId = clientId == null ? "" : clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret;
        this.json = json;
        this.retry = retry;
        this.clock = clock;
    }

    /** A valid bearer token, fetching or refreshing one if needed. */
    public String accessToken() {
        CachedToken current = cached;
        Instant now = clock.instant();
        if (current != null && current.usableAt(now)) {
            return current.value();
        }
        synchronized (this) {
            // Another thread may have refreshed while we waited for the lock.
            CachedToken fresh = cached;
            if (fresh != null && fresh.usableAt(clock.instant())) {
                return fresh.value();
            }
            CachedToken token = fetch();
            cached = token;
            return token.value();
        }
    }

    /** Drop the cached token (used after a 401, so the next call re-authenticates). */
    public void invalidate() {
        cached = null;
    }

    private CachedToken fetch() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new PayPalApiException(401, "MISSING_CREDENTIALS",
                    "PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET must be set to call the PayPal sandbox.",
                    null, List.of());
        }
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        return retry.execute("oauth2/token", true, () -> {
            try {
                return rest.post()
                        .uri("/v1/oauth2/token")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .body("grant_type=client_credentials")
                        .exchange((request, response) -> {
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            int status = response.getStatusCode().value();
                            if (status >= 400) {
                                throw PayPalApiException.fromResponse(status, body, json);
                            }
                            JsonNode n = json.readTree(body);
                            String value = n.path("access_token").asString(null);
                            if (value == null || value.isBlank()) {
                                throw new PayPalApiException(status, "INVALID_TOKEN_RESPONSE",
                                        "PayPal token response contained no access_token.", null, List.of());
                            }
                            long expiresIn = n.path("expires_in").asLong(3600L);
                            Duration life = Duration.ofSeconds(Math.max(expiresIn, 1));
                            Duration margin = life.compareTo(REFRESH_MARGIN.multipliedBy(2)) > 0
                                    ? REFRESH_MARGIN
                                    : life.dividedBy(2);
                            log.debug("Fetched PayPal access token, expires in {}s", expiresIn);
                            return new CachedToken(value, clock.instant().plus(life).minus(margin));
                        });
            } catch (ResourceAccessException e) {
                throw PayPalApiException.transport("oauth2/token", e);
            }
        });
    }
}
