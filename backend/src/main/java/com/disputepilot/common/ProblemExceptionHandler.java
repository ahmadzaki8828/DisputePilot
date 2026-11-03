package com.disputepilot.common;

import com.disputepilot.paypal.PayPalApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Errors are RFC 7807 problem JSON (application/problem+json). */
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setTitle(e.title());
        return p;
    }

    @ExceptionHandler(PayPalApiException.class)
    ProblemDetail handlePayPal(PayPalApiException e) {
        log.warn("PayPal error {} {} debug_id={} issues={}", e.httpStatus(), e.name(), e.debugId(), e.issues());
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        p.setTitle("PayPal request failed");
        p.setProperty("paypalName", e.name());
        p.setProperty("paypalDebugId", e.debugId());
        p.setProperty("paypalIssues", e.issues());
        return p;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred.");
        p.setTitle("Internal server error");
        return p;
    }
}
