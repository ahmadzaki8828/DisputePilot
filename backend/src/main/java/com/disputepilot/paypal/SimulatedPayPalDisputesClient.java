package com.disputepilot.paypal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-memory stand-in for the PayPal Disputes API, seeded from fixtures/paypal/disputes/*.json.
 * It mimics PayPal's response shapes, status transitions and errors, including the links[] that drive
 * which actions are allowed. Disputes served here have IDs starting "DEMO-" and are always "simulated".
 *
 * <p>Fixture due dates are stored relative to {@link #FIXTURE_EPOCH} and re-based to "now" at load time,
 * so the due-in countdown in the UI is always realistic.
 */
@Component
public class SimulatedPayPalDisputesClient implements PayPalDisputesClient {

    public static final String DEMO_PREFIX = "DEMO-";
    static final Instant FIXTURE_EPOCH = Instant.parse("2026-11-03T00:00:00Z");
    private static final String WAITING_FOR_SELLER = "WAITING_FOR_SELLER_RESPONSE";

    private static final PayPalDisputeMapper MAPPER = new PayPalDisputeMapper();

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AtomicInteger createdSequence = new AtomicInteger();
    private final Map<String, ObjectNode> disputes = new LinkedHashMap<>();
    private final Map<String, JsonNode> fullLinks = new LinkedHashMap<>();
    private final Map<String, ObjectNode> templates = new LinkedHashMap<>();

    public SimulatedPayPalDisputesClient() {
        reset();
    }

    /** Reload all fixtures, discarding in-memory changes. */
    public final synchronized void reset() {
        disputes.clear();
        fullLinks.clear();
        templates.clear();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:fixtures/paypal/disputes/*.json");
            Arrays.sort(files, Comparator.comparing(Resource::getFilename));
            Instant now = Instant.now();
            for (Resource file : files) {
                try (InputStream in = file.getInputStream()) {
                    ObjectNode node = (ObjectNode) mapper.readTree(in);
                    rebase(node, "seller_response_due_date", now, false);
                    rebase(node, "create_time", now, true);
                    rebase(node, "update_time", now, true);
                    node.path("messages").forEach(m -> rebase((ObjectNode) m, "time_posted", now, true));
                    node.remove("_note");
                    String id = node.path("dispute_id").asString();
                    fullLinks.put(id, node.get("links").deepCopy());
                    templates.put(id, node.deepCopy());
                    disputes.put(id, node);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load dispute fixtures", e);
        }
    }

    /**
     * Fixture timestamps are written relative to {@link #FIXTURE_EPOCH} and shifted to "now" at load time, so
     * the due-in countdown in the UI is always realistic. Timestamps that record something that already
     * happened ({@code create_time}, {@code update_time}, message times) are never allowed into the future.
     */
    private static void rebase(ObjectNode node, String field, Instant now, boolean inThePast) {
        String value = node.path(field).asString(null);
        if (value == null || value.isBlank()) {
            return;
        }
        Instant shifted = now.plus(Duration.between(FIXTURE_EPOCH, Instant.parse(value)));
        if (inThePast && shifted.isAfter(now)) {
            shifted = now;
        }
        node.put(field, shifted.toString());
    }

    /** The fixture dispute IDs that {@link #createFromTemplate} can clone, in fixture order. */
    public synchronized List<String> templates() {
        return List.copyOf(templates.keySet());
    }

    /**
     * Clone one of the fixture disputes under a fresh {@code DEMO-} ID, as if a buyer had just opened it, and
     * return it. Used by the demo endpoints so a judge can create a dispute and watch it travel the whole
     * webhook -> outbox -> agent -> submit pipeline.
     */
    public synchronized PayPalDispute createFromTemplate(String template) {
        ObjectNode source = templates.get(template);
        if (source == null) {
            throw error(404, "RESOURCE_NOT_FOUND", "No such dispute template: " + template, "INVALID_RESOURCE_ID");
        }
        String templateId = source.path("dispute_id").asString();
        String id = DEMO_PREFIX + "D-" + (9000 + createdSequence.incrementAndGet());
        Instant now = Instant.now();
        ObjectNode copy = source.deepCopy();
        copy.put("dispute_id", id);
        copy.put("create_time", now.toString());
        copy.put("update_time", now.toString());
        copy.put("status", WAITING_FOR_SELLER);
        copy.remove("dispute_outcome");
        copy.put("seller_response_due_date", now.plus(Duration.ofDays(9)).toString());
        ArrayNode links = mapper.createArrayNode();
        for (JsonNode l : source.path("links")) {
            ObjectNode link = (ObjectNode) l.deepCopy();
            link.put("href", link.path("href").asString("").replace(templateId, id));
            links.add(link);
        }
        copy.set("links", links);
        fullLinks.put(id, links.deepCopy());
        disputes.put(id, copy);
        return toDispute(copy);
    }

    /** Forget every dispute created through {@link #createFromTemplate} and restore the fixture set. */
    public synchronized void resetToFixtures() {
        reset();
    }

    @Override
    public synchronized DisputePage listDisputes(DisputeQuery query) {
        List<PayPalDispute> items = disputes.values().stream()
                .map(this::toDispute)
                .filter(d -> query == null || query.status() == null || query.status().equals(d.status()))
                .filter(d -> query == null || query.updatedAfter() == null
                        || (d.updateTime() != null && d.updateTime().isAfter(query.updatedAfter())))
                .toList();
        return new DisputePage(items, null);
    }

    @Override
    public synchronized PayPalDispute getDispute(String disputeId) {
        return toDispute(require(disputeId));
    }

    @Override
    public synchronized void provideEvidence(String disputeId, EvidenceSubmission s) {
        ObjectNode d = requireAction(disputeId, "provide_evidence");
        if (s == null || s.evidences() == null || s.evidences().isEmpty()) {
            throw error(400, "INVALID_REQUEST", "Request is not well-formed, syntactically incorrect, or violates schema.",
                    "MISSING_REQUIRED_PARAMETER");
        }
        ArrayNode evidences = d.withArray("evidences");
        s.evidences().forEach(e -> {
            ObjectNode n = mapper.valueToTree(e);
            n.put("source", "SUBMITTED_BY_SELLER");
            evidences.add(n);
        });
        transition(d, "UNDER_REVIEW");
    }

    @Override
    public synchronized void acceptClaim(String disputeId, AcceptClaimRequest r) {
        ObjectNode d = requireAction(disputeId, "accept_claim");
        resolve(d, "RESOLVED_BUYER_FAVOUR");
    }

    @Override
    public synchronized void makeOffer(String disputeId, OfferRequest r) {
        ObjectNode d = requireAction(disputeId, "make_offer");
        if (r != null && r.offerAmount() != null) {
            BigDecimal disputed = new BigDecimal(d.path("dispute_amount").path("value").asString());
            if (r.offerAmount().value().compareTo(disputed) > 0) {
                throw error(422, "UNPROCESSABLE_ENTITY", "The offer amount exceeds the disputed amount.",
                        "OFFER_AMOUNT_EXCEEDS_DISPUTE_AMOUNT");
            }
        }
        transition(d, "WAITING_FOR_BUYER_RESPONSE");
    }

    @Override
    public synchronized void sendMessage(String disputeId, String message) {
        ObjectNode d = require(disputeId);
        if ("RESOLVED".equals(d.path("status").asString())) {
            throw error(422, "UNPROCESSABLE_ENTITY", "The dispute is resolved; messages are not allowed.",
                    "DISPUTE_ALREADY_RESOLVED");
        }
        ObjectNode m = mapper.createObjectNode();
        m.put("posted_by", "SELLER");
        m.put("time_posted", Instant.now().toString());
        m.put("content", message);
        d.withArray("messages").add(m);
        d.put("update_time", Instant.now().toString());
    }

    @Override
    public synchronized void requireEvidence(String disputeId, String action) {
        ObjectNode d = require(disputeId);
        if (!"UNDER_REVIEW".equals(d.path("status").asString()) || !"SELLER_EVIDENCE".equals(action)) {
            throw error(422, "UNPROCESSABLE_ENTITY", "Dispute is not in a state where evidence can be required.",
                    "DISPUTE_STATE_NOT_VALID_FOR_ACTION");
        }
        transition(d, WAITING_FOR_SELLER);
    }

    @Override
    public synchronized void adjudicate(String disputeId, String outcome) {
        ObjectNode d = require(disputeId);
        if (!"UNDER_REVIEW".equals(d.path("status").asString())) {
            throw error(422, "UNPROCESSABLE_ENTITY", "Dispute must be UNDER_REVIEW to adjudicate.",
                    "DISPUTE_STATE_NOT_VALID_FOR_ACTION");
        }
        switch (outcome == null ? "" : outcome) {
            case "SELLER_FAVOR" -> resolve(d, "RESOLVED_SELLER_FAVOUR");
            case "BUYER_FAVOR" -> resolve(d, "RESOLVED_BUYER_FAVOUR");
            default -> throw error(400, "INVALID_REQUEST", "adjudication_outcome must be BUYER_FAVOR or SELLER_FAVOR.",
                    "INVALID_PARAMETER_VALUE");
        }
    }

    @Override
    public WebhookVerification verifyWebhook(WebhookHeaders h, String rawBody) {
        // Simulated disputes never arrive as signed PayPal deliveries.
        return new WebhookVerification(false, "SKIPPED_SIMULATED");
    }

    // ---- internals ----

    private ObjectNode require(String disputeId) {
        ObjectNode d = disputes.get(disputeId);
        if (d == null) {
            throw error(404, "RESOURCE_NOT_FOUND", "The specified resource does not exist.", "INVALID_RESOURCE_ID");
        }
        return d;
    }

    private ObjectNode requireAction(String disputeId, String rel) {
        ObjectNode d = require(disputeId);
        if (!allowedRels(d).contains(rel)) {
            throw error(422, "UNPROCESSABLE_ENTITY",
                    "The requested action is not allowed while the dispute is " + d.path("status").asString() + ".",
                    "DISPUTE_STATE_NOT_VALID_FOR_ACTION");
        }
        return d;
    }

    private void transition(ObjectNode d, String status) {
        d.put("status", status);
        d.put("update_time", Instant.now().toString());
        refreshLinks(d);
    }

    private void resolve(ObjectNode d, String outcomeCode) {
        d.putObject("dispute_outcome").put("outcome_code", outcomeCode);
        transition(d, "RESOLVED");
    }

    /** Allowed actions change with status: the full link set only while waiting for the seller. */
    private void refreshLinks(ObjectNode d) {
        String id = d.path("dispute_id").asString();
        ArrayNode links = mapper.createArrayNode();
        for (JsonNode l : fullLinks.get(id)) {
            String rel = l.path("rel").asString();
            boolean keep = "self".equals(rel)
                    || WAITING_FOR_SELLER.equals(d.path("status").asString())
                    || ("send_message".equals(rel) && !"RESOLVED".equals(d.path("status").asString()));
            if (keep) {
                links.add(l);
            }
        }
        d.set("links", links);
    }

    private List<String> allowedRels(ObjectNode d) {
        List<String> rels = new ArrayList<>();
        d.path("links").forEach(l -> rels.add(l.path("rel").asString()));
        return rels;
    }

    /** Mapping is shared with the real client so both produce identical domain objects. */
    private PayPalDispute toDispute(ObjectNode d) {
        return MAPPER.toDispute(d);
    }

    private static PayPalApiException error(int status, String name, String message, String issue) {
        return new PayPalApiException(status, name, message, "SIM-" + Long.toHexString(System.nanoTime()), List.of(issue));
    }
}
