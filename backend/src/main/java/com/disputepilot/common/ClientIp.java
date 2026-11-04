package com.disputepilot.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The address a request is rate-limited by. Every {@code /api/**} call carries a valid admin token, so the caller
 * is the dashboard's own server, which forwards the visitor's address in {@code X-Forwarded-For}; direct callers
 * (curl, tests) are identified by their socket address.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 64) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
