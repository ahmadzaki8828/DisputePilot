package com.disputepilot.domain;

import java.util.List;

public record ValidationResult(boolean passed, List<Issue> issues) {

    public record Issue(String code, String message, String path) {
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }
}
