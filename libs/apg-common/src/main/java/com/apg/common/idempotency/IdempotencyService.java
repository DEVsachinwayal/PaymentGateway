package com.apg.common.idempotency;

import com.apg.common.api.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotency for money-changing POSTs (spec 6.2). The marker row and the business change commit in ONE
 * database transaction, so a failed attempt leaves no marker and can be retried with the same key, while a
 * concurrent duplicate blocks on the unique key until the first commits and then replays its response.
 *
 * Each service owns the table (see {@link #TABLE_DDL}). Same key + same fingerprint => original response;
 * same key + different fingerprint => 422 IDEMPOTENCY_KEY_REUSED.
 */
public class IdempotencyService {
    public static final String TABLE_DDL = """
            CREATE TABLE idempotency_keys (
                merchant_id     UUID         NOT NULL,
                idem_key        VARCHAR(100) NOT NULL,
                request_hash    VARCHAR(64)  NOT NULL,
                status          VARCHAR(12)  NOT NULL,   -- IN_PROGRESS | COMPLETED
                response_status INT,
                response_body   TEXT,
                created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                PRIMARY KEY (merchant_id, idem_key)
            );""";

    /** @param replay true when the stored response of an earlier identical request is returned */
    public record Outcome(int status, String body, boolean replay) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Outcome execute(UUID merchantId, String key, Object request, int successStatus, Supplier<Object> action) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key header (max 100 chars) is required");
        }
        String hash = fingerprint(request);
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (merchant_id, idem_key, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS') ON CONFLICT (merchant_id, idem_key) DO NOTHING""", merchantId, key, hash);

        if (inserted == 0) {
            var row = jdbc.queryForMap("""
                    SELECT request_hash, status, response_status, response_body FROM idempotency_keys
                    WHERE merchant_id = ? AND idem_key = ?""", merchantId, key);
            if (!hash.equals(row.get("request_hash"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            if (!"COMPLETED".equals(row.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed");
            }
            return new Outcome((Integer) row.get("response_status"), (String) row.get("response_body"), true);
        }

        Object result = action.get();
        String body = write(result);
        jdbc.update("""
                UPDATE idempotency_keys SET status = 'COMPLETED', response_status = ?, response_body = ?
                WHERE merchant_id = ? AND idem_key = ?""", successStatus, body, merchantId, key);
        return new Outcome(successStatus, body, false);
    }

    private String fingerprint(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
