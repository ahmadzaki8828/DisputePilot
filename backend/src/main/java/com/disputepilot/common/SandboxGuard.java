package com.disputepilot.common;

import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Startup guard: the app only ever talks to the PayPal sandbox. There is no live mode.
 * Anything that is not https on a *.sandbox.paypal.com host (including api-m.paypal.com) is refused.
 */
@Component
public class SandboxGuard implements InitializingBean {

    private final AppProperties props;

    public SandboxGuard(AppProperties props) {
        this.props = props;
    }

    @Override
    public void afterPropertiesSet() {
        requireSandbox(props.paypal().baseUrl());
    }

    public static void requireSandbox(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAYPAL_BASE_URL is not a valid URL", e);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean sandbox = host.equals("sandbox.paypal.com") || host.endsWith(".sandbox.paypal.com");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !sandbox) {
            throw new IllegalStateException(
                    "Refusing to start: PAYPAL_BASE_URL must be an https *.sandbox.paypal.com host "
                            + "(DisputePilot is sandbox-only; there is no live mode). Got host '" + host + "'.");
        }
    }
}
