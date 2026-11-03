package com.disputepilot.evals;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import com.disputepilot.merchant.MerchantDataPort;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Merchant records carried inside an eval case, so a case is self-contained. */
public class InMemoryMerchantData implements MerchantDataPort {

    private final List<Order> orders;
    private final List<Shipment> shipments;
    private final List<CustomerMessage> messages;
    private final StorePolicy policy;

    public InMemoryMerchantData(JsonNode node, JsonMapper mapper) {
        this.orders = read(mapper, node.path("orders"), new TypeReference<List<Order>>() { });
        this.shipments = read(mapper, node.path("shipments"), new TypeReference<List<Shipment>>() { });
        this.messages = read(mapper, node.path("messages"), new TypeReference<List<CustomerMessage>>() { });
        this.policy = node.path("policy").isObject()
                ? mapper.readValue(mapper.treeAsTokens(node.path("policy")), StorePolicy.class)
                : null;
    }

    private static <T> List<T> read(JsonMapper mapper, JsonNode node, TypeReference<List<T>> type) {
        return node.isArray() ? mapper.readValue(mapper.treeAsTokens(node), type) : List.of();
    }

    @Override
    public Optional<Order> findOrderByPaypalTransactionId(String txnId) {
        return orders.stream().filter(o -> o.paypalTransactionId().equals(txnId)).findFirst();
    }

    @Override
    public List<Shipment> shipmentsForOrder(String orderId) {
        return shipments.stream().filter(s -> s.orderId().equals(orderId)).toList();
    }

    @Override
    public List<CustomerMessage> messagesForOrder(String orderId) {
        return messages.stream().filter(m -> m.orderId().equals(orderId)).toList();
    }

    @Override
    public StorePolicy policy() {
        return policy;
    }
}
