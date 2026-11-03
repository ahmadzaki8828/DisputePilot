package com.disputepilot;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Database for integration tests. Uses a Testcontainers Postgres 16 by default. When Docker is not usable
 * (e.g. some cloud VMs) set DP_TEST_DB_URL / DP_TEST_DB_USER / DP_TEST_DB_PASSWORD to point at a local Postgres.
 */
public final class PostgresTestSupport {

    private static PostgreSQLContainer container;

    private PostgresTestSupport() {
    }

    public static synchronized void register(DynamicPropertyRegistry registry) {
        String url = System.getenv("DP_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> env("DP_TEST_DB_USER", "disputepilot"));
            registry.add("spring.datasource.password", () -> env("DP_TEST_DB_PASSWORD", "disputepilot"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine");
            container.start();
        }
        PostgreSQLContainer c = container;
        registry.add("spring.datasource.url", c::getJdbcUrl);
        registry.add("spring.datasource.username", c::getUsername);
        registry.add("spring.datasource.password", c::getPassword);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
