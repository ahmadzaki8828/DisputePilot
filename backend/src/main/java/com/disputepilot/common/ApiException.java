package com.disputepilot.common;

import org.springframework.http.HttpStatus;

/** Thrown by controllers; rendered as RFC 7807 problem JSON by {@link ProblemExceptionHandler}. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String title;

    public ApiException(HttpStatus status, String title, String detail) {
        super(detail);
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() { return status; }
    public String title() { return title; }

    public static ApiException notImplemented(String what) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, "Not implemented",
                what + " is not implemented yet.");
    }
}
