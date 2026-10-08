package com.apg.webhook.web;

import com.apg.common.api.ApiError;
import com.apg.common.api.ApiException;
import com.apg.common.api.ApiResponse;
import com.apg.webhook.service.WebhookProperties;
import com.apg.webhook.service.WebhookService;
import com.apg.webhook.service.WebhookService.Outcome;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Public endpoint for provider callbacks. Two callers share it:
 *  - the customer's BROWSER (PayU posts the form to surl/furl) -> we answer with a 303 back to the merchant;
 *  - the provider's SERVER (webhook) -> we answer 200 JSON.
 * Authenticity is decided only by the provider's signature, checked in provider-integration.
 */
@RestController
public class WebhookController {
    private final WebhookService service;
    private final WebhookProperties props;

    public WebhookController(WebhookService service, WebhookProperties props) {
        this.service = service;
        this.props = props;
    }

    @PostMapping(value = "/api/v1/webhooks/{provider}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<?> form(@PathVariable String provider, @RequestBody MultiValueMap<String, String> form,
                           @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        Map<String, String> fields = new LinkedHashMap<>();
        form.forEach((k, v) -> fields.put(k, v.isEmpty() ? "" : v.get(0)));
        return respond(provider, fields, accept);
    }

    @PostMapping(value = "/api/v1/webhooks/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> json(@PathVariable String provider, @RequestBody Map<String, Object> body,
                           @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        Map<String, String> fields = new LinkedHashMap<>();
        body.forEach((k, v) -> {
            if (v instanceof String || v instanceof Number || v instanceof Boolean) fields.put(k, String.valueOf(v));
        });
        return respond(provider, fields, accept);
    }

    private ResponseEntity<?> respond(String provider, Map<String, String> fields, String accept) {
        if (fields.size() > props.maxFields() || fields.values().stream().anyMatch(v -> v.length() > props.maxValueLength())) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Callback too large");
        }
        boolean browser = accept != null && accept.contains("text/html");
        Outcome o = service.handle(provider, fields);

        if (!o.authentic()) {
            if (browser) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.TEXT_HTML)
                        .body("<!doctype html><html><head><meta charset=\"utf-8\"><title>Payment</title></head><body style=\"font-family:system-ui,sans-serif;text-align:center;margin-top:20vh\"><p>This payment response could not be verified.</p></body></html>");
            }
            return ResponseEntity.badRequest().body(ApiResponse.fail(ApiError.of("INVALID_SIGNATURE", "Callback could not be authenticated")));
        }
        if (browser) {
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(nextUrl(o)).header("Referrer-Policy", "no-referrer")
                    .header("Cache-Control", "no-store").build();
        }
        return ResponseEntity.ok(Map.of("status", "received"));
    }

    /** Where the customer goes next: the merchant's returnUrl, or APG's neutral result page. Carries no secrets. */
    private URI nextUrl(Outcome o) {
        String status = o.payment() == null ? "UNKNOWN" : o.payment().status();
        String base = o.payment() != null && o.payment().returnUrl() != null ? o.payment().returnUrl() : props.defaultResultPath();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(base).queryParam("status", status);
        if (o.payment() != null) b.queryParam("paymentId", o.payment().paymentId());
        return b.build(true).toUri();
    }
}
