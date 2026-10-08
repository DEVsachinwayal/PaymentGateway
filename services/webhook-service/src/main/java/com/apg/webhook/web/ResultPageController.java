package com.apg.webhook.web;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Neutral landing page for customers when the merchant supplied no returnUrl. It only shows a message chosen
 * from a fixed list - never any request text - and is a hint: merchants must confirm via the API.
 */
@RestController
public class ResultPageController {
    private static final Map<String, String> MESSAGES = Map.of(
            "SUCCESS", "Payment successful. Thank you!",
            "FAILED", "The payment did not go through. You have not been charged.",
            "PENDING", "Your payment is being confirmed. Please do not pay again.",
            "PROCESSING", "Your payment is being confirmed. Please do not pay again.",
            "VERIFY_REQUIRED", "Your payment is being confirmed. Please do not pay again.");

    @GetMapping(value = "/payment-result", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> result(@RequestParam(required = false) String status) {
        String msg = MESSAGES.getOrDefault(status == null ? "" : status, "We could not determine the payment status. Please check with the merchant.");
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Payment status</title></head><body style=\"font-family:system-ui,sans-serif;text-align:center;margin-top:20vh\"><p style=\"font-size:20px\">"
                + msg + "</p></body></html>";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                .header("X-Content-Type-Options", "nosniff").body(html);
    }
}
