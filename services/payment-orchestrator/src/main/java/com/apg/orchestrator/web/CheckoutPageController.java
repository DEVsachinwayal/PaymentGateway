package com.apg.orchestrator.web;

import com.apg.common.api.ApiException;
import com.apg.orchestrator.client.ProviderClient.Checkout;
import com.apg.orchestrator.service.CheckoutService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hosted redirect page: the customer's browser lands here (public, token protected) and is forwarded to the
 * provider's checkout by an auto-submitted form. Provider secrets never appear: only the signed form fields.
 */
@RestController
public class CheckoutPageController {
    private final CheckoutService checkout;
    private final SecureRandom random = new SecureRandom();

    public CheckoutPageController(CheckoutService checkout) { this.checkout = checkout; }

    @GetMapping(value = "/checkout/{token}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> page(@PathVariable String token) {
        Checkout c;
        try {
            c = checkout.redirectInstructions(token);
        } catch (ApiException e) {
            HttpStatus s = e.status();
            return secure(ResponseEntity.status(s), null).body(message(switch (e.code()) {
                case "CHECKOUT_EXPIRED" -> "This payment link has expired. Please return to the merchant and try again.";
                case "PAYMENT_NOT_PAYABLE" -> "This payment is already completed or closed.";
                default -> "This payment link is not valid.";
            }));
        }
        String nonce = nonce();
        StringBuilder inputs = new StringBuilder();
        for (Map.Entry<String, String> f : c.fields().entrySet()) {
            inputs.append("<input type=\"hidden\" name=\"").append(esc(f.getKey())).append("\" value=\"").append(esc(f.getValue())).append("\">\n");
        }
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Redirecting to payment</title><style nonce=\"" + nonce + "\">body{font-family:system-ui,sans-serif;margin:0;display:grid;place-items:center;min-height:100vh;background:#f6f7f9;color:#1b1f24}"
                + ".card{background:#fff;padding:32px 28px;border-radius:12px;box-shadow:0 1px 6px rgba(0,0,0,.12);max-width:360px;text-align:center}"
                + "button{margin-top:16px;padding:10px 20px;font-size:16px;border:0;border-radius:8px;background:#1d4ed8;color:#fff;cursor:pointer}</style></head><body>"
                + "<div class=\"card\"><h1 style=\"font-size:20px\">Taking you to the secure payment page&hellip;</h1>"
                + "<p>If nothing happens, press the button.</p>"
                + "<form id=\"pay\" method=\"" + esc(c.method().toLowerCase()) + "\" action=\"" + esc(c.url()) + "\">\n" + inputs
                + "<button type=\"submit\">Continue to payment</button></form></div>"
                + "<script nonce=\"" + nonce + "\">document.getElementById('pay').submit();</script></body></html>";
        return secure(ResponseEntity.ok(), nonce).body(html);
    }

    private ResponseEntity.BodyBuilder secure(ResponseEntity.BodyBuilder b, String nonce) {
        String n = nonce == null ? nonce() : nonce;
        return b.contentType(MediaType.TEXT_HTML).cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", "default-src 'none'; style-src 'nonce-" + n + "'; script-src 'nonce-" + n
                        + "'; base-uri 'none'; frame-ancestors 'none'")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer");
    }

    private String message(String text) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Payment</title></head><body style=\"font-family:system-ui,sans-serif;text-align:center;margin-top:20vh\"><p>"
                + esc(text) + "</p></body></html>";
    }

    private String nonce() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getEncoder().withoutPadding().encodeToString(b).replace('+', 'a').replace('/', 'b');
    }

    static String esc(String s) {
        if (s == null) return "";
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
