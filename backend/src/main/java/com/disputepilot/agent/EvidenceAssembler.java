package com.disputepilot.agent;

import com.disputepilot.domain.CustomerMessage;
import com.disputepilot.domain.DisputeReason;
import com.disputepilot.domain.Order;
import com.disputepilot.domain.Shipment;
import com.disputepilot.domain.StorePolicy;
import com.disputepilot.merchant.MerchantDataPort;
import com.disputepilot.paypal.PayPalDispute;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Builds the agent's {@link CaseFile} from a dispute plus the merchant's own records, giving every fact a
 * stable citation ID and redacting buyer PII (plan §9.2 steps 1-2). Nothing else assembles model input.
 */
@Component
public class EvidenceAssembler {

    private final MerchantDataPort merchant;

    public EvidenceAssembler(MerchantDataPort merchant) {
        this.merchant = merchant;
    }

    /** The case file, and the redactor that produced it (the validator needs the same one). */
    public record Assembled(CaseFile caseFile, PiiRedactor redactor) {
    }

    public Assembled assemble(PayPalDispute dispute) {
        Order order = merchant.findOrderByPaypalTransactionId(dispute.disputedTransactionId()).orElse(null);
        PiiRedactor redactor = new PiiRedactor(order);

        List<Shipment> shipments = order == null ? List.of() : merchant.shipmentsForOrder(order.id());
        List<CustomerMessage> messages = order == null ? List.of() : merchant.messagesForOrder(order.id());

        CaseFile caseFile = new CaseFile(
                disputeFacts(dispute),
                orderFacts(order, redactor),
                shipmentFacts(shipments, redactor),
                messageFacts(messages, redactor),
                disputeMessageFacts(dispute.raw(), redactor),
                policyFacts(merchant.policy(), redactor));
        return new Assembled(caseFile, redactor);
    }

    private static CaseFile.DisputeFacts disputeFacts(PayPalDispute d) {
        return new CaseFile.DisputeFacts(
                "dispute:" + d.paypalDisputeId(),
                d.paypalDisputeId(),
                d.simulated(),
                d.reason(),
                DisputeReason.labelFor(d.reason()),
                d.status(),
                d.lifeCycleStage(),
                d.amount(),
                d.sellerResponseDueAt(),
                d.requestedEvidence() == null ? List.of() : d.requestedEvidence(),
                d.allowedActions() == null ? List.of() : d.allowedActions(),
                d.disputedTransactionId());
    }

    private static CaseFile.OrderFacts orderFacts(Order o, PiiRedactor redactor) {
        if (o == null) {
            return null;
        }
        List<CaseFile.ItemFacts> items = new ArrayList<>();
        if (o.items() != null) {
            o.items().forEach(i -> items.add(new CaseFile.ItemFacts(i.sku(), i.name(), i.quantity(), i.unitPrice())));
        }
        List<CaseFile.RefundFacts> refunds = new ArrayList<>();
        if (o.refunds() != null) {
            o.refunds().forEach(r -> refunds.add(
                    new CaseFile.RefundFacts(r.id(), r.amount(), r.issuedAt(), redactor.redact(r.reason()))));
        }
        Order.Address a = o.shippingAddress();
        // Street line and postal code are deliberately dropped; city/region/country are enough to argue delivery.
        return new CaseFile.OrderFacts(
                "order:" + o.id(),
                o.id(),
                o.paypalTransactionId(),
                o.placedAt(),
                o.buyerName(),
                a == null ? null : a.city(),
                a == null ? null : a.region(),
                a == null ? null : a.country(),
                items,
                o.total(),
                o.status(),
                refunds);
    }

    private static List<CaseFile.ShipmentFacts> shipmentFacts(List<Shipment> shipments, PiiRedactor redactor) {
        List<CaseFile.ShipmentFacts> out = new ArrayList<>();
        for (Shipment s : shipments) {
            List<String> events = new ArrayList<>();
            if (s.events() != null) {
                s.events().forEach(e -> events.add(e.at() + " " + redactor.redact(e.description())));
            }
            out.add(new CaseFile.ShipmentFacts(
                    "shipment:" + s.id(), s.id(), s.carrier(), s.trackingNumber(), s.status(),
                    s.shippedAt(), s.deliveredAt(), s.signedBy(), s.deliveredToCity(), events));
        }
        return out;
    }

    private static List<CaseFile.MessageFacts> messageFacts(List<CustomerMessage> messages, PiiRedactor redactor) {
        List<CaseFile.MessageFacts> out = new ArrayList<>();
        for (CustomerMessage m : messages) {
            out.add(new CaseFile.MessageFacts(
                    "msg:" + m.id(), m.id(), m.at(), m.from(), redactor.redact(m.body())));
        }
        return out;
    }

    private static List<CaseFile.DisputeMessageFacts> disputeMessageFacts(JsonNode raw, PiiRedactor redactor) {
        List<CaseFile.DisputeMessageFacts> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int i = 0;
        for (JsonNode m : raw.path("messages")) {
            i++;
            String posted = m.path("time_posted").asString(null);
            out.add(new CaseFile.DisputeMessageFacts(
                    "msg:DM-" + i,
                    posted == null ? null : parse(posted),
                    m.path("posted_by").asString(null),
                    redactor.redact(m.path("content").asString(null))));
        }
        return out;
    }

    private static void section(List<CaseFile.PolicySection> target, String citationId, String name, String text,
                                PiiRedactor redactor) {
        if (text != null && !text.isBlank()) {
            target.add(new CaseFile.PolicySection(citationId, name, redactor.redact(text)));
        }
    }

    private static Instant parse(String value) {
        return Optional.ofNullable(value).map(Instant::parse).orElse(null);
    }

    private static CaseFile.PolicyFacts policyFacts(StorePolicy p, PiiRedactor redactor) {
        if (p == null) {
            return null;
        }
        List<CaseFile.PolicySection> sections = new ArrayList<>();
        section(sections, "policy:returns", "returns", p.returns(), redactor);
        section(sections, "policy:shipping", "shipping", p.shipping(), redactor);
        section(sections, "policy:refunds", "refunds", p.refunds(), redactor);
        section(sections, "policy:descriptions", "productDescriptions", p.productDescriptions(), redactor);
        return new CaseFile.PolicyFacts(p.merchantName(), sections);
    }
}
