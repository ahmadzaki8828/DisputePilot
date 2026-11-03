package com.disputepilot.api;

import com.disputepilot.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.server.PathContainer;
import java.lang.reflect.Method;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Every endpoint in plan §7 that S0 does not implement answers 501 with problem JSON.
 *
 * <p>The stubs are served by a low-priority handler mapping, not by controllers, so a session that adds the real
 * controller for one of these routes simply takes over (the real mapping is consulted first) - no stub has to be
 * deleted and no duplicate-mapping error can occur.
 */
@Configuration
public class PlannedEndpointStubs {

    record Route(String method, PathPattern pattern, String description) {
    }

    private static Route route(String method, String path) {
        return new Route(method, PathPatternParser.defaultInstance.parse(path), method + " " + path);
    }

    static final List<Route> ROUTES = List.of(
            route("POST", "/webhooks/paypal"),
            route("POST", "/api/disputes/{id}/sync"),
            route("POST", "/api/sync"),
            route("POST", "/api/disputes/{id}/drafts"),
            route("GET", "/api/disputes/{id}/drafts/latest"),
            route("PATCH", "/api/drafts/{id}"),
            route("POST", "/api/drafts/{id}/approve"),
            route("POST", "/api/drafts/{id}/submit"),
            route("GET", "/api/audit"),
            route("GET", "/api/metrics"),
            route("GET", "/api/evals/latest"),
            route("GET", "/api/system"),
            route("POST", "/api/demo/disputes"),
            route("POST", "/api/demo/reset"),
            route("POST", "/api/dev/link-transaction"));

    /** Target of every stub route; throws 501 for the current request. */
    public static class NotImplementedStub {
        public void notImplemented(HttpServletRequest request) {
            throw ApiException.notImplemented(request.getMethod() + " " + request.getRequestURI());
        }
    }

    private static final HandlerMethod STUB_HANDLER = stubHandler();

    private static HandlerMethod stubHandler() {
        try {
            Method m = NotImplementedStub.class.getMethod("notImplemented", HttpServletRequest.class);
            return new HandlerMethod(new NotImplementedStub(), m);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Bean
    AbstractHandlerMapping plannedEndpointStubMapping() {
        AbstractHandlerMapping mapping = new AbstractHandlerMapping() {
            @Override
            protected Object getHandlerInternal(HttpServletRequest request) {
                PathContainer path = PathContainer.parsePath(request.getRequestURI());
                for (Route r : ROUTES) {
                    if (r.method().equals(request.getMethod()) && r.pattern().matches(path)) {
                        // A HandlerMethod (not a plain handler) so @RestControllerAdvice renders the problem JSON.
                        return STUB_HANDLER;
                    }
                }
                return null;
            }
        };
        // After RequestMappingHandlerMapping (order 0), before the static resource handler.
        mapping.setOrder(Ordered.LOWEST_PRECEDENCE - 2);
        return mapping;
    }
}
