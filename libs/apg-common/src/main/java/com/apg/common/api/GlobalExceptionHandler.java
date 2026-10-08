package com.apg.common.api;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.fail(ApiError.of(e.code(), e.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        List<ApiError.FieldIssue> issues = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldIssue(f.getField(), f.getDefaultMessage())).toList();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(new ApiError("VALIDATION_FAILED", "Request validation failed", issues)));
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> handleMalformed(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ApiError.of("MALFORMED_REQUEST", "Malformed request")));
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(ApiError.of("NOT_FOUND", "Resource not found")));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMethod(Exception e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ApiError.of("METHOD_NOT_ALLOWED", "Method not allowed")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // Spring MVC's own protocol errors (415 unsupported media type, 406, 400 missing header, ...) carry their status.
        if (e instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            HttpStatus s = HttpStatus.valueOf(er.getStatusCode().value());
            return ResponseEntity.status(s).body(ApiResponse.fail(ApiError.of("HTTP_" + s.value(), s.getReasonPhrase())));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ApiError.of("INTERNAL_ERROR", "Unexpected error")));
    }
}
