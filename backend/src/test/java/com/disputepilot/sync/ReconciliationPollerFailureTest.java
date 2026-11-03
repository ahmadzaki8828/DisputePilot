package com.disputepilot.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.disputepilot.domain.Money;
import com.disputepilot.paypal.DisputePage;
import com.disputepilot.paypal.DisputeQuery;
import com.disputepilot.paypal.PayPalApiException;
import com.disputepilot.paypal.PayPalDispute;
import com.disputepilot.paypal.PayPalDisputesClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How reconciliation behaves when PayPal misbehaves. The cursor is the only thing standing between a missed
 * webhook and a dispute nobody looks at again, so it must not move past a dispute that failed to sync.
 */
class ReconciliationPollerFailureTest {

    private static final SyncProperties PROPS = new SyncProperties(false, Duration.ofSeconds(5), 20, 5,
            Duration.ofMinutes(10), 50, 3, Duration.ofDays(7));

    private final PayPalDisputesClient paypal = mock(PayPalDisputesClient.class);
    private final DisputeSyncService sync = mock(DisputeSyncService.class);
    private final SyncStateRepository state = mock(SyncStateRepository.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final ReconciliationPoller poller =
            new ReconciliationPoller(paypal, sync, state, audit, PROPS);

    private static PayPalDispute dispute(String id, Instant updated) {
        return new PayPalDispute(id, id.startsWith("DEMO-"), "UNAUTHORISED", "OPEN", "INQUIRY",
                new Money("CAD", new BigDecimal("10.00")), null, "TXN-1", List.of(), List.of(), null, updated,
                null);
    }

    @Test
    void aFailedDisputeHoldsTheCursorBackSoTheNextRunRetriesIt() {
        Instant t1 = Instant.parse("2026-11-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-02T10:00:00Z");
        when(state.cursor()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(paypal.listDisputes(any())).thenReturn(new DisputePage(
                List.of(dispute("PP-1", t1), dispute("PP-2", t2)), null));
        when(sync.sync(eq("PP-1"), anyString()))
                .thenReturn(new DisputeSyncService.SyncResult(UUID.randomUUID(), dispute("PP-1", t1)));
        when(sync.sync(eq("PP-2"), anyString()))
                .thenThrow(new PayPalApiException(503, "INTERNAL_SERVER_ERROR", "later", "dbg", List.of()));

        ReconciliationPoller.Summary summary = poller.runOnce();

        assertThat(summary.listed()).isEqualTo(2);
        assertThat(summary.synced()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(state, never()).advanceCursor(any());
    }

    @Test
    void aCleanRunAdvancesTheCursorToTheNewestUpdateTime() {
        Instant t1 = Instant.parse("2026-11-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-02T10:00:00Z");
        when(state.cursor()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(paypal.listDisputes(any())).thenReturn(new DisputePage(
                List.of(dispute("PP-1", t1), dispute("PP-2", t2)), null));
        when(sync.sync(eq("PP-1"), anyString()))
                .thenReturn(new DisputeSyncService.SyncResult(UUID.randomUUID(), dispute("PP-1", t1)));
        when(sync.sync(eq("PP-2"), anyString()))
                .thenReturn(new DisputeSyncService.SyncResult(UUID.randomUUID(), dispute("PP-2", t2)));

        poller.runOnce();

        verify(state).advanceCursor(t2);
    }

    @Test
    void theFirstRunLooksBackOverTheConfiguredWindow() {
        when(state.cursor()).thenReturn(Optional.empty());
        when(paypal.listDisputes(any())).thenReturn(new DisputePage(List.of(), null));

        poller.runOnce();

        verify(paypal).listDisputes(org.mockito.ArgumentMatchers.argThat(q ->
                q.updatedAfter() != null
                        && q.updatedAfter().isAfter(Instant.now().minus(Duration.ofDays(8)))
                        && q.updatedAfter().isBefore(Instant.now().minus(Duration.ofDays(6)))));
    }

    @Test
    void pagingStopsAtTheConfiguredPageLimit() {
        when(state.cursor()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(paypal.listDisputes(any())).thenReturn(new DisputePage(List.of(), "MORE"));

        poller.runOnce();

        // 3 pages configured: a PayPal cursor that never ends cannot keep the poller running.
        verify(paypal, times(3)).listDisputes(any());
    }

    @Test
    void aFailureToListIsReportedRatherThanThrown() {
        when(state.cursor()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(paypal.listDisputes(any()))
                .thenThrow(new PayPalApiException(429, "RATE_LIMIT_REACHED", "slow down", "dbg", List.of()));

        ReconciliationPoller.Summary summary = poller.runOnce();

        assertThat(summary.listed()).isZero();
        assertThat(summary.synced()).isZero();
        verify(sync, never()).sync(anyString(), anyString());
    }

    @Test
    void pagingFollowsPayPalsNextPageToken() {
        Instant t = Instant.parse("2026-11-01T10:00:00Z");
        when(state.cursor()).thenReturn(Optional.of(Instant.parse("2026-10-01T00:00:00Z")));
        when(paypal.listDisputes(any()))
                .thenReturn(new DisputePage(List.of(dispute("PP-1", t)), "PAGE-2"))
                .thenReturn(new DisputePage(List.of(dispute("PP-2", t)), null));
        when(sync.sync(anyString(), anyString()))
                .thenReturn(new DisputeSyncService.SyncResult(UUID.randomUUID(), dispute("PP-1", t)));

        assertThat(poller.runOnce().synced()).isEqualTo(2);
        verify(paypal).listDisputes(org.mockito.ArgumentMatchers.argThat(DisputeQueryHasToken.none()));
        verify(paypal).listDisputes(org.mockito.ArgumentMatchers.argThat(DisputeQueryHasToken.of("PAGE-2")));
    }

    /** Readable matcher for the page token on a {@link DisputeQuery}. */
    private interface DisputeQueryHasToken {
        static org.mockito.ArgumentMatcher<DisputeQuery> none() {
            return q -> q.nextPageToken() == null;
        }

        static org.mockito.ArgumentMatcher<DisputeQuery> of(String token) {
            return q -> token.equals(q.nextPageToken());
        }
    }
}
