package com.disputepilot.agent;

import com.disputepilot.domain.Order;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces buyer PII with stable tokens before anything reaches the model, and detects PII (or leftover
 * tokens) on the way back out. Buyer email, phone and street address never leave the backend.
 *
 * <p>Redaction is value-based first (the exact strings from the merchant's own order record) and then
 * pattern-based, so PII that only appears inside free-text buyer messages is caught too.
 */
public final class PiiRedactor {

    public static final String EMAIL_TOKEN = "[BUYER_EMAIL]";
    public static final String PHONE_TOKEN = "[BUYER_PHONE]";
    public static final String ADDRESS_TOKEN = "[BUYER_ADDRESS]";

    /** Every token this redactor can emit; none of them may appear in text sent to PayPal. */
    public static final List<String> TOKENS = List.of(EMAIL_TOKEN, PHONE_TOKEN, ADDRESS_TOKEN);

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Phone numbers, e.g. +1-416-555-0101, (416) 555 0101, 416.555.0101. Deliberately narrow so that
    // tracking numbers (NLC4417002931) and ISO dates (2026-10-21) are never mistaken for phone numbers.
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w-])(?:\\+\\d{1,3}[\\s.-]?)?(?:\\(\\d{3}\\)|\\d{3})[\\s.-]\\d{3}[\\s.-]\\d{4}(?![\\w-])");
    // A street line: house number followed by a street-ish word.
    private static final Pattern STREET = Pattern.compile(
            "\\b\\d{1,6}\\s+[A-Za-z][A-Za-z.'-]*(\\s+[A-Za-z][A-Za-z.'-]*){0,3}\\s+"
                    + "(Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Way|Crescent|Cres|Place|Pl)\\b",
            Pattern.CASE_INSENSITIVE);

    private final List<String> emails = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();
    private final List<String> addresses = new ArrayList<>();

    public PiiRedactor() {
    }

    /** A redactor that also knows the exact PII values on this order. */
    public PiiRedactor(Order order) {
        if (order == null) {
            return;
        }
        add(emails, order.buyerEmail());
        add(phones, order.buyerPhone());
        if (order.shippingAddress() != null) {
            add(addresses, order.shippingAddress().line1());
        }
    }

    private static void add(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    /** Buyer PII becomes a token. Null in, null out. */
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = text;
        for (String e : emails) {
            out = out.replace(e, EMAIL_TOKEN);
        }
        for (String p : phones) {
            out = out.replace(p, PHONE_TOKEN);
        }
        for (String a : addresses) {
            out = out.replace(a, ADDRESS_TOKEN);
        }
        out = EMAIL.matcher(out).replaceAll(Matcher.quoteReplacement(EMAIL_TOKEN));
        out = STREET.matcher(out).replaceAll(Matcher.quoteReplacement(ADDRESS_TOKEN));
        out = PHONE.matcher(out).replaceAll(Matcher.quoteReplacement(PHONE_TOKEN));
        return out;
    }

    /** True when the text still carries raw buyer PII (an email, phone or street address). */
    public boolean containsPii(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String known : concat()) {
            if (text.contains(known)) {
                return true;
            }
        }
        return EMAIL.matcher(text).find() || STREET.matcher(text).find() || PHONE.matcher(text).find();
    }

    /** True when the text still carries one of this redactor's tokens; those must never go to PayPal. */
    public static boolean containsRedactionToken(String text) {
        if (text == null) {
            return false;
        }
        return TOKENS.stream().anyMatch(text::contains);
    }

    private List<String> concat() {
        List<String> all = new ArrayList<>(emails.size() + phones.size() + addresses.size());
        all.addAll(emails);
        all.addAll(phones);
        all.addAll(addresses);
        return all;
    }
}
