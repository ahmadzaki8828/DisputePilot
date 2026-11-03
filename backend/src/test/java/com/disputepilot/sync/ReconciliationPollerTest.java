package com.disputepilot.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.PostgresTestSupport;
import com.disputepilot.domain.EvidenceItem;
import com.disputepilot.paypal.AcceptClaimRequest;
import com.disputepilot.paypal.EvidenceSubmission;
import com.disputepilot.paypal.SimulatedPayPalDisputesClient;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Reconciliation against the simulated PayPal: cursor handling and self-healing (plan §8.3). */
@SpringBootTest(properties = {"dp.admin-token=test-token", "dp.paypal.mode=mock"})
class ReconciliationPollerTest {

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        PostgresTestSupport.register(registry);
    }

    @Autowired JdbcClient jdbc;
    @Autowired ReconciliationPoller poller;
    @Autowired SyncStateRepository state;
    @Autowired SimulatedPayPalDisputesClient simulated;

    @BeforeEach
    void clean() {
        jdbc.sql("truncate table submissions, drafts, disputes, webhook_events, audit_log, sync_state cascade")
                .update();
        simulated.resetToFixtures();
    }

    private int disputeCount() {
        return jdbc.sql("select count(*) from disputes").query(Integer.class).single();
    }

    @Test
    void theFirstRunPicksUpEveryDisputeAndStoresACursor() {
        ReconciliationPoller.Summary summary = poller.runOnce();

        assertThat(summary.listed()).isEqualTo(6);
        assertThat(summary.synced()).isEqualTo(6);
        assertThat(summary.failed()).isZero();
        assertThat(disputeCount()).isEqualTo(6);
        assertThat(state.cursor()).isPresent();
        assertThat(state.cursor().orElseThrow()).isBeforeOrEqualTo(Instant.now());
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'DISPUTES_RECONCILED'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void aSecondRunWithNothingNewDoesNoWork() {
        poller.runOnce();
        Instant cursor = state.cursor().orElseThrow();

        ReconciliationPoller.Summary again = poller.runOnce();

        assertThat(again.listed()).isZero();
        assertThat(again.synced()).isZero();
        assertThat(disputeCount()).isEqualTo(6);
        assertThat(state.cursor()).hasValue(cursor);
    }

    @Test
    void onlyDisputesChangedSinceTheCursorAreResynced() {
        poller.runOnce();
        Instant firstCursor = state.cursor().orElseThrow();

        // A missed webhook: PayPal moved this dispute on and never told us.
        simulated.provideEvidence("DEMO-D-1002", new EvidenceSubmission(
                List.of(new EvidenceItem("PROOF_OF_FULFILLMENT", null, "Tracking added"))));

        ReconciliationPoller.Summary healed = poller.runOnce();

        assertThat(healed.listed()).isEqualTo(1);
        assertThat(healed.synced()).isEqualTo(1);
        assertThat(jdbc.sql("select status from disputes where paypal_dispute_id = 'DEMO-D-1002'")
                .query(String.class).single()).isEqualTo("UNDER_REVIEW");
        assertThat(state.cursor().orElseThrow()).isAfter(firstCursor);
        assertThat(disputeCount()).isEqualTo(6);
    }

    @Test
    void aDisputeResolvedBehindOurBackIsHealedWithItsOutcome() {
        poller.runOnce();
        simulated.acceptClaim("DEMO-D-1004", new AcceptClaimRequest("accepted elsewhere", null));

        poller.runOnce();

        assertThat(jdbc.sql("""
                select outcome from disputes where paypal_dispute_id = 'DEMO-D-1004'
                """).query(String.class).single()).isEqualTo("RESOLVED_BUYER_FAVOUR");
        assertThat(jdbc.sql("""
                select status from disputes where paypal_dispute_id = 'DEMO-D-1004'
                """).query(String.class).single()).isEqualTo("RESOLVED");
    }

    @Test
    void aDisputeCreatedAfterTheCursorIsPickedUpWithoutAnyWebhook() {
        poller.runOnce();

        String created = simulated.createFromTemplate("DEMO-D-1003").paypalDisputeId();

        assertThat(poller.runOnce().synced()).isEqualTo(1);
        assertThat(disputeCount()).isEqualTo(7);
        assertThat(jdbc.sql("select count(*) from disputes where paypal_dispute_id = :id")
                .param("id", created).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theCursorOnlyEverMovesForward() {
        poller.runOnce();
        Instant cursor = state.cursor().orElseThrow();

        state.advanceCursor(cursor.minusSeconds(3600));

        assertThat(state.cursor()).hasValue(cursor);
    }
}
