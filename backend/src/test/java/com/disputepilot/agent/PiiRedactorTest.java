package com.disputepilot.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.domain.Money;
import com.disputepilot.domain.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PiiRedactorTest {

    private static Order order() {
        return new Order("ORD-1042", "TXN-8F3A1001", Instant.parse("2026-10-15T13:00:00Z"),
                "Jordan Ellis", "jordan.ellis@buyer.example", "+1-416-555-0101",
                new Order.Address("1201 Example Street", "Toronto", "ON", "M5V 0A0", "CA"),
                List.of(), new Money("CAD", new BigDecimal("180.00")), "FULFILLED", List.of());
    }

    @Test
    void replacesTheOrdersEmailPhoneAndStreet() {
        PiiRedactor r = new PiiRedactor(order());
        String out = r.redact("Reach Jordan at jordan.ellis@buyer.example or +1-416-555-0101, 1201 Example Street.");
        assertThat(out).contains(PiiRedactor.EMAIL_TOKEN, PiiRedactor.PHONE_TOKEN, PiiRedactor.ADDRESS_TOKEN);
        assertThat(out).doesNotContain("jordan.ellis@buyer.example", "416-555-0101", "1201 Example Street");
    }

    @Test
    void replacesPiiPatternsItHasNeverSeenBefore() {
        PiiRedactor r = new PiiRedactor();
        assertThat(r.redact("write to someone.else@example.org")).contains(PiiRedactor.EMAIL_TOKEN);
        assertThat(r.redact("call (416) 555 0199")).contains(PiiRedactor.PHONE_TOKEN);
        assertThat(r.redact("deliver to 55 Sample Avenue")).contains(PiiRedactor.ADDRESS_TOKEN);
    }

    @Test
    void leavesTrackingNumbersDatesAndMoneyAlone() {
        PiiRedactor r = new PiiRedactor(order());
        String text = "Tracking NLC4417002931 delivered 2026-10-21T16:40:00Z, CAD 180.00, refund REF-5001.";
        assertThat(r.redact(text)).isEqualTo(text);
        assertThat(r.containsPii(text)).isFalse();
    }

    @Test
    void containsPiiFindsRawValuesAndPatterns() {
        PiiRedactor r = new PiiRedactor(order());
        assertThat(r.containsPii("ship to 1201 Example Street")).isTrue();
        assertThat(r.containsPii("mail jordan.ellis@buyer.example")).isTrue();
        assertThat(r.containsPii("ring +1-416-555-0101")).isTrue();
        assertThat(r.containsPii("Delivered and signed by J. Ellis on 2026-10-21.")).isFalse();
    }

    @Test
    void detectsLeftoverRedactionTokens() {
        assertThat(PiiRedactor.containsRedactionToken("Delivered to [BUYER_ADDRESS].")).isTrue();
        assertThat(PiiRedactor.containsRedactionToken("Delivered and signed for.")).isFalse();
    }

    @Test
    void nullAndBlankSurviveUntouched() {
        PiiRedactor r = new PiiRedactor(order());
        assertThat(r.redact(null)).isNull();
        assertThat(r.redact("")).isEmpty();
        assertThat(r.containsPii(null)).isFalse();
    }
}
