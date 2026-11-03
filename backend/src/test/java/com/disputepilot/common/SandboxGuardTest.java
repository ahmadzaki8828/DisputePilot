package com.disputepilot.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SandboxGuardTest {

    @Test
    void acceptsTheSandboxHost() {
        assertThatCode(() -> SandboxGuard.requireSandbox("https://api-m.sandbox.paypal.com")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api-m.paypal.com",
            "https://api.paypal.com",
            "https://sandbox.paypal.com.evil.example",
            "https://evilsandbox.paypal.com",
            "http://api-m.sandbox.paypal.com",
            "http://localhost:8089",
            "",
            "not a url"})
    void refusesAnythingElse(String url) {
        assertThatThrownBy(() -> SandboxGuard.requireSandbox(url)).isInstanceOf(IllegalStateException.class);
    }
}
