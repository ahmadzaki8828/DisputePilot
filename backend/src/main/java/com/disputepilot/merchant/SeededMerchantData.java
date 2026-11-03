package com.disputepilot.merchant;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.type.TypeReference;

/** Merchant data for the fictional store "Maple &amp; Thread Co.", loaded in memory from fixtures/merchant/*.json. */
@Component
public class SeededMerchantData implements MerchantDataPort {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<Order> orders;
    private final List<Shipment> shipments;
    private final List<CustomerMessage> messages;
    private final StorePolicy policy;

    public SeededMerchantData() {
        this.orders = readList("fixtures/merchant/orders.json", "orders", new TypeReference<List<Order>>() { });
        this.shipments = readList("fixtures/merchant/shipments.json", "shipments", new TypeReference<List<Shipment>>() { });
        this.messages = readList("fixtures/merchant/messages.json", "messages", new TypeReference<List<CustomerMessage>>() { });
        this.policy = read("fixtures/merchant/policy.json", StorePolicy.class);
    }

    @Override
    public Optional<Order> findOrderByPaypalTransactionId(String txnId) {
        if (txnId == null) {
            return Optional.empty();
        }
        return orders.stream().filter(o -> txnId.equals(o.paypalTransactionId())).findFirst();
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

    private static <T> T read(String path, Class<T> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }

    private static <T> List<T> readList(String path, String field, TypeReference<List<T>> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            return MAPPER.readValue(MAPPER.treeAsTokens(root.get(field)), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
    }
}
