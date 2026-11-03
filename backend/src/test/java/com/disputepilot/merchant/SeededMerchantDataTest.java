package com.disputepilot.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.disputepilot.domain.Order;
import org.junit.jupiter.api.Test;

class SeededMerchantDataTest {

    private final SeededMerchantData data = new SeededMerchantData();

    @Test
    void findsOrderAndShipmentForFixtureTransaction() {
        Order order = data.findOrderByPaypalTransactionId("TXN-8F3A1001").orElseThrow();
        assertThat(order.id()).isEqualTo("ORD-1042");
        assertThat(order.total().value()).isEqualByComparingTo("180.00");

        var shipments = data.shipmentsForOrder("ORD-1042");
        assertThat(shipments).hasSize(1);
        assertThat(shipments.get(0).carrier()).isEqualTo("Northline Courier");
        assertThat(shipments.get(0).trackingNumber()).isEqualTo("NLC4417002931");
        assertThat(shipments.get(0).signedBy()).isNotBlank();
    }

    @Test
    void orderWithoutShipmentHasNone() {
        assertThat(data.shipmentsForOrder("ORD-1043")).isEmpty();
    }

    @Test
    void refundIsRecordedOnTheCreditNotProcessedOrder() {
        Order order = data.findOrderByPaypalTransactionId("TXN-8F3A1005").orElseThrow();
        assertThat(order.refunds()).extracting(Order.Refund::id).containsExactly("REF-5001");
    }

    @Test
    void policyAndMessagesLoad() {
        assertThat(data.policy().merchantName()).isEqualTo("Maple & Thread Co.");
        assertThat(data.messagesForOrder("ORD-1042")).isNotEmpty();
        assertThat(data.findOrderByPaypalTransactionId("nope")).isEmpty();
    }
}
