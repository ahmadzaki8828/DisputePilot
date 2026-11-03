package com.disputepilot.merchant;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
    private final TransactionLinks links;

    /** Without a database: no linked transactions (unit tests, the eval runner). */
    public SeededMerchantData() {
        this(null);
    }

    @Autowired
    public SeededMerchantData(TransactionLinks links) {
        this.links = links;
        List<Order> o = new ArrayList<>(readList("fixtures/merchant/orders.json", "orders", new TypeReference<List<Order>>() { }));
        List<Shipment> s = new ArrayList<>(readList("fixtures/merchant/shipments.json", "shipments", new TypeReference<List<Shipment>>() { }));
        List<CustomerMessage> m = new ArrayList<>(readList("fixtures/merchant/messages.json", "messages", new TypeReference<List<CustomerMessage>>() { }));
        // Extra records behind the demo templates (fixtures/paypal/templates); purely additive files.
        for (String extra : extraFiles()) {
            o.addAll(readOptionalList(extra, "orders", new TypeReference<List<Order>>() { }));
            s.addAll(readOptionalList(extra, "shipments", new TypeReference<List<Shipment>>() { }));
            m.addAll(readOptionalList(extra, "messages", new TypeReference<List<CustomerMessage>>() { }));
        }
        this.orders = List.copyOf(o);
        this.shipments = List.copyOf(s);
        this.messages = List.copyOf(m);
        this.policy = read("fixtures/merchant/policy.json", StorePolicy.class);
    }

    @Override
    public Optional<Order> findOrderByPaypalTransactionId(String txnId) {
        if (txnId == null) {
            return Optional.empty();
        }
        // A transaction the user linked to an order wins over the seeded mapping.
        if (links != null) {
            Optional<Order> linked = links.orderIdFor(txnId).flatMap(this::findOrderById)
                    .map(o -> new Order(o.id(), txnId, o.placedAt(), o.buyerName(), o.buyerEmail(), o.buyerPhone(),
                            o.shippingAddress(), o.items(), o.total(), o.status(), o.refunds()));
            if (linked.isPresent()) {
                return linked;
            }
        }
        return orders.stream().filter(o -> txnId.equals(o.paypalTransactionId())).findFirst();
    }

    @Override
    public Optional<Order> findOrderById(String orderId) {
        return orderId == null ? Optional.empty()
                : orders.stream().filter(o -> orderId.equals(o.id())).findFirst();
    }

    private static List<String> extraFiles() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:fixtures/merchant/extra-*.json");
            return java.util.Arrays.stream(files).map(f -> "fixtures/merchant/" + f.getFilename()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list extra merchant fixtures", e);
        }
    }

    private static <T> List<T> readOptionalList(String path, String field, TypeReference<List<T>> type) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            return root.has(field) ? MAPPER.readValue(MAPPER.treeAsTokens(root.get(field)), type) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + path, e);
        }
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
