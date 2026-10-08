package com.apg.common.api;

import java.util.List;

/** Stable machine-readable error (spec 6.3). */
public record ApiError(String code, String message, List<FieldIssue> details) {
    public record FieldIssue(String field, String issue) {}

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }
}
