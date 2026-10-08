package com.apg.common.api;

import com.apg.common.web.CorrelationIdFilter;
import java.time.Instant;
import org.slf4j.MDC;

/** Standard response envelope (spec 6.3). */
public record ApiResponse<T>(boolean success, T data, ApiError error, String requestId, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, currentRequestId(), Instant.now());
    }

    public static ApiResponse<Void> fail(ApiError error) {
        return new ApiResponse<>(false, null, error, currentRequestId(), Instant.now());
    }

    private static String currentRequestId() {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        return id == null ? "req_unknown" : id;
    }
}
