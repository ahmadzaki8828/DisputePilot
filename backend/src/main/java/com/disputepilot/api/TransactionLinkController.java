package com.disputepilot.api;

import com.disputepilot.common.ApiException;
import com.disputepilot.common.AppProperties;
import com.disputepilot.common.audit.AuditLogWriter;
import com.disputepilot.merchant.MerchantDataPort;
import com.disputepilot.merchant.TransactionLinks;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/dev/link-transaction}: map a real sandbox transaction to one of the fictional store's seeded
 * orders so a real sandbox dispute can be answered with the demo records. Available in mock mode, and elsewhere
 * only when {@code DP_DEV_ENDPOINTS=true}.
 */
@RestController
public class TransactionLinkController {

    public record LinkRequest(String sellerTransactionId, String orderId) {
    }

    private final AppProperties props;
    private final MerchantDataPort merchant;
    private final TransactionLinks links;
    private final AuditLogWriter audit;

    public TransactionLinkController(AppProperties props, MerchantDataPort merchant, TransactionLinks links,
                                     AuditLogWriter audit) {
        this.props = props;
        this.merchant = merchant;
        this.links = links;
        this.audit = audit;
    }

    @PostMapping("/api/dev/link-transaction")
    public Map<String, Object> link(@RequestBody(required = false) LinkRequest body) {
        if (props.paypal().mode() != AppProperties.PaypalMode.MOCK && !props.devEndpoints()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Dev endpoints disabled",
                    "Linking a transaction needs DP_DEV_ENDPOINTS=true when PAYPAL_MODE is not mock.");
        }
        if (body == null || isBlank(body.sellerTransactionId()) || isBlank(body.orderId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Missing fields",
                    "Send both sellerTransactionId and orderId.");
        }
        String txn = body.sellerTransactionId().trim();
        String orderId = body.orderId().trim();
        if (txn.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid transaction id", "That id is too long.");
        }
        if (merchant.findOrderById(orderId).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Order not found",
                    "The demo store has no order " + orderId + ".");
        }
        links.link(txn, orderId, "user");
        audit.user(AuditLogWriter.TRANSACTION_LINKED, null, Map.of("sellerTransactionId", txn, "orderId", orderId));
        return Map.of("sellerTransactionId", txn, "orderId", orderId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
