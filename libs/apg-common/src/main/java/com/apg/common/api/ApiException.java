package com.apg.common.api;

import org.springframework.http.HttpStatus;

/** Business exception carrying a stable error code and HTTP status. */
public class ApiException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
