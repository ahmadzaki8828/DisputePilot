package com.disputepilot.agent;

/** A model-provider call failed. Carries the HTTP status when there was one (0 for transport errors). */
public class LlmException extends RuntimeException {

    private final int httpStatus;
    private final String provider;

    public LlmException(String provider, int httpStatus, String message) {
        super(message);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public LlmException(String provider, int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String provider() {
        return provider;
    }

    /** 429 and 5xx are worth another attempt; a transport error (status 0) is too. */
    public boolean retryable() {
        return httpStatus == 429 || httpStatus == 0 || httpStatus >= 500;
    }
}
