package com.disputepilot.paypal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.disputepilot.common.SandboxGuard;
import com.disputepilot.domain.EvidenceItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only test that talks to PayPal. It is skipped unless {@code PAYPAL_MODE=sandbox} and sandbox
 * credentials are present, so {@code ./mvnw verify} and CI never make a network call; run it deliberately with
 *
 * <pre>PAYPAL_MODE=sandbox PAYPAL_CLIENT_ID=… PAYPAL_CLIENT_SECRET=… ./mvnw -B test -Dtest=SandboxSmokeTest</pre>
 *
 * <p>It checks the two things that can only be checked for real: that client-credentials auth works against
 * the sandbox, and that a dispute action comes back shaped the way the error mapping expects. The negative
 * case uses PayPal's own error-simulation dispute ID {@code ERRDIS039} (plan §8.2).
 */
class SandboxSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(SandboxSmokeTest.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private String baseUrl;
    private HttpPayPalDisputesClient client;

    @BeforeEach
    void requireSandboxCredentials() {
        String mode = env("PAYPAL_MODE");
        String clientId = env("PAYPAL_CLIENT_ID");
        String clientSecret = env("PAYPAL_CLIENT_SECRET");
        Assumptions.assumeTrue("sandbox".equalsIgnoreCase(mode),
                "PAYPAL_MODE is not 'sandbox'; skipping the live sandbox smoke test");
        Assumptions.assumeTrue(!clientId.isBlank() && !clientSecret.isBlank(),
                "No sandbox credentials; skipping the live sandbox smoke test");

        baseUrl = env("PAYPAL_BASE_URL").isBlank() ? "https://api-m.sandbox.paypal.com" : env("PAYPAL_BASE_URL");
        // Belt and braces: never let this test point at anything but the sandbox.
        SandboxGuard.requireSandbox(baseUrl);

        var rest = com.disputepilot.paypal.PayPalClientConfig.restClient(baseUrl, Duration.ofSeconds(10),
                Duration.ofSeconds(30));
        // One attempt only: a smoke test should report the first answer PayPal gives, not retry around it.
        PayPalRetryPolicy once = new PayPalRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
        client = new HttpPayPalDisputesClient(rest,
                new PayPalTokenProvider(rest, clientId, clientSecret, JSON, once),
                JSON, new PayPalDisputeMapper(), once, env("PAYPAL_WEBHOOK_ID"));
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }

    @Test
    void authenticatesAndListsDisputes() {
        DisputePage page = client.listDisputes(new DisputeQuery(null, null, 5, null));

        assertThat(page).isNotNull();
        assertThat(page.items()).isNotNull();
        log.info("Sandbox listDisputes returned {} dispute(s) from {}", page.items().size(), baseUrl);
        for (PayPalDispute d : page.items()) {
            assertThat(d.paypalDisputeId()).isNotBlank();
            assertThat(d.simulated()).isFalse();
            log.info("  {} {} {} allowed={}", d.paypalDisputeId(), d.reason(), d.status(), d.allowedActions());
        }
    }

    @Test
    void aFetchedDisputeMapsCleanly() {
        DisputePage page = client.listDisputes(new DisputeQuery(null, null, 1, null));
        Assumptions.assumeFalse(page.items().isEmpty(),
                "This sandbox account has no disputes yet; create one with the PayPal sandbox tools first");

        PayPalDispute d = client.getDispute(page.items().get(0).paypalDisputeId());

        assertThat(d.status()).isNotBlank();
        assertThat(d.reason()).isNotBlank();
        assertThat(d.amount()).isNotNull();
        assertThat(d.raw().path("dispute_id").asString()).isEqualTo(d.paypalDisputeId());
        // Allowed actions must come from PayPal's own links[], so an empty list is a legitimate answer.
        assertThat(d.allowedActions()).isNotNull();
    }

    @Test
    void theErrorSimulationDisputeIsMappedWithItsDebugId() {
        // ERRDIS039 is PayPal's negative-testing dispute ID (plan §8.2). Whatever this sandbox account
        // answers - a simulated failure, or RESOURCE_NOT_FOUND because the ID is not provisioned for it -
        // the point of the test is that the answer arrives as a PayPalApiException carrying PayPal's own
        // name, message and debug_id, which is what makes a failed submission diagnosable.
        assertThatThrownBy(() -> client.getDispute("ERRDIS039"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    log.info("GET ERRDIS039 -> status={} name={} debug_id={} issues={}", e.httpStatus(),
                            e.name(), e.debugId(), e.issues());
                    assertThat(e.httpStatus()).isGreaterThanOrEqualTo(400);
                    assertThat(e.name()).isNotBlank();
                    assertThat(e.getMessage()).isNotBlank();
                    assertThat(e.debugId()).as("PayPal sends debug_id on every error").isNotBlank();
                });

        assertThatThrownBy(() -> client.requireEvidence("ERRDIS039", "SELLER_EVIDENCE"))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    log.info("POST ERRDIS039/require-evidence -> status={} name={} debug_id={} issues={}",
                            e.httpStatus(), e.name(), e.debugId(), e.issues());
                    assertThat(e.httpStatus()).isGreaterThanOrEqualTo(400);
                    assertThat(e.debugId()).isNotBlank();
                });
    }

    /**
     * The multipart provide-evidence request against the real sandbox. Opt-in via
     * {@code DP_SANDBOX_MULTIPART_SMOKE=true}, because some egress proxies (including the one in the cloud
     * container this project was built in) stall on a multipart upload to PayPal and the request never
     * returns - with curl just as with this client. The body shape itself is pinned by
     * {@link HttpPayPalDisputesClientTest#provideEvidenceSendsMultipartWithAJsonInputPart()}.
     */
    @Test
    void provideEvidenceReachesTheSandboxWhenMultipartUploadsAreAllowed() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(env("DP_SANDBOX_MULTIPART_SMOKE")),
                "Set DP_SANDBOX_MULTIPART_SMOKE=true to exercise a real multipart provide-evidence call");

        assertThatThrownBy(() -> client.provideEvidence("ERRDIS039", new EvidenceSubmission(
                List.of(new EvidenceItem("OTHER", null,
                        "Smoke test of DisputePilot error handling; no real evidence.")))))
                .isInstanceOfSatisfying(PayPalApiException.class, e -> {
                    log.info("POST ERRDIS039/provide-evidence -> status={} name={} debug_id={}", e.httpStatus(),
                            e.name(), e.debugId());
                    assertThat(e.httpStatus()).isGreaterThanOrEqualTo(400);
                    assertThat(e.name()).isNotBlank();
                });
    }
}
