package com.disputepilot.merchant;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import java.util.List;
import java.util.Optional;

/** Read-only access to the merchant's own records. */
public interface MerchantDataPort {
    Optional<Order> findOrderByPaypalTransactionId(String txnId);

    List<Shipment> shipmentsForOrder(String orderId);

    List<CustomerMessage> messagesForOrder(String orderId);

    StorePolicy policy();
}
