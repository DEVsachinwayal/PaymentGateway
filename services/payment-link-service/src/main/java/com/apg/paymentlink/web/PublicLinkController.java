package com.apg.paymentlink.web;

import com.apg.common.api.ApiException;
import com.apg.paymentlink.service.LinkService;
import com.apg.paymentlink.service.LinkService.NotPayable;
import com.apg.paymentlink.service.LinkService.PublicLink;
import java.net.URI;
import java.security.SecureRandom;
import java.text.NumberFormat;
import java.util.Base64;
import java.util.Currency;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The page a customer sees when opening a payment link, and the Pay button's target. Public (no login): the link code is the only
 * credential and only allows paying that one fixed amount. Everything merchant-provided is HTML-escaped; no script runs on the page.
 */
@RestController
public class PublicLinkController {
    private static final Logger log = LoggerFactory.getLogger(PublicLinkController.class);
    private final LinkService links;
    private final SecureRandom random = new SecureRandom();

    public PublicLinkController(LinkService links) {
        this.links = links;
    }

    @GetMapping(value = "/pay/{code}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> show(@PathVariable String code) {
        var link = links.publicLink(code);
        if (link.isEmpty()) return page(HttpStatus.NOT_FOUND, "Link not found", "<p>This payment link does not exist or was mistyped.</p>");
        PublicLink l = link.get();
        return switch (l.status()) {
            case "ACTIVE" -> page(HttpStatus.OK, "Pay " + esc(l.merchantName()), card(l, form(l)));
            case "PAID" -> page(HttpStatus.GONE, "Already paid", card(l, "<p class=\"ok\">This payment link has already been paid. Thank you!</p>"));
            case "EXPIRED" -> page(HttpStatus.GONE, "Link expired", card(l, "<p class=\"warn\">This payment link has expired." + contact(l) + "</p>"));
            default -> page(HttpStatus.GONE, "Link not available", card(l, "<p class=\"warn\">This payment link is no longer active." + contact(l) + "</p>"));
        };
    }

    @PostMapping(value = "/pay/{code}/start")
    ResponseEntity<String> start(@PathVariable String code) {
        try {
            String url = links.start(code);
            return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(url)).header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
        } catch (NotPayable e) {
            return switch (e.kind) {
                case "IN_PROGRESS" -> page(HttpStatus.CONFLICT, "Payment in progress", "<p class=\"warn\">A payment for this link is already in progress. If you just paid, wait a minute and refresh this page; otherwise you can try again in about 15 minutes (a payment that was not completed is released automatically).</p>");
                case "NOT_FOUND" -> page(HttpStatus.NOT_FOUND, "Link not found", "<p>This payment link does not exist.</p>");
                case "PAID" -> page(HttpStatus.GONE, "Already paid", "<p class=\"ok\">This payment link has already been paid.</p>");
                default -> page(HttpStatus.GONE, "Link not available", "<p class=\"warn\">This payment link is no longer active.</p>");
            };
        } catch (ApiException e) {
            log.warn("payment link start failed: {}", e.code());
            return page(HttpStatus.BAD_GATEWAY, "Could not start the payment", "<p class=\"warn\">We could not start the payment right now. Nothing was charged. Please try again in a moment.</p><p><a href=\"/pay/" + esc(code) + "\">Back</a></p>");
        }
    }

    // ------------------------------------------------------------------ html

    private String card(PublicLink l, String inner) {
        String desc = l.description() == null ? "" : "<p class=\"desc\">" + esc(l.description()) + "</p>";
        return "<div class=\"merchant\">" + esc(l.merchantName()) + "</div>" + desc
                + "<div class=\"amount\">" + esc(money(l.amount(), l.currency())) + "</div>" + inner;
    }

    private String form(PublicLink l) {
        return "<form method=\"post\" action=\"/pay/" + esc(l.code()) + "/start\"><button type=\"submit\">Pay " + esc(money(l.amount(), l.currency()))
                + "</button></form><p class=\"fine\">You will be taken to our payment partner's secure page to pay by card, UPI or net banking. We never see your card details.</p>";
    }

    private static String contact(PublicLink l) {
        return l.merchantSupport() == null ? "" : " Please contact " + esc(l.merchantName()) + " (" + esc(l.merchantSupport()) + ").";
    }

    private ResponseEntity<String> page(HttpStatus status, String title, String body) {
        byte[] n = new byte[16];
        random.nextBytes(n);
        String nonce = Base64.getEncoder().encodeToString(n);
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(title)
                + "</title><meta name=\"robots\" content=\"noindex\"><style nonce=\"" + nonce + "\">"
                + "body{margin:0;min-height:100vh;display:grid;place-items:center;background:linear-gradient(160deg,#eef0ff,#f7f8fb);font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#151a33}"
                + "main{background:#fff;border-radius:18px;box-shadow:0 10px 40px rgba(30,30,80,.12);padding:32px;max-width:420px;width:calc(100% - 32px);text-align:center}"
                + ".merchant{font-weight:600;color:#4f46e5;margin-bottom:6px}.desc{color:#6b7194;margin:4px 0 12px}.amount{font-size:40px;font-weight:700;letter-spacing:-.02em;margin:10px 0 22px}"
                + "button{width:100%;padding:14px;border:0;border-radius:12px;background:linear-gradient(135deg,#4f46e5,#7c3aed);color:#fff;font-size:17px;font-weight:600;cursor:pointer}"
                + ".fine{font-size:12px;color:#6b7194;margin-top:14px}.ok{color:#047857;font-weight:600}.warn{color:#b45309}a{color:#4f46e5}</style></head><body><main>" + body + "</main></body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'nonce-" + nonce + "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
                .header("X-Content-Type-Options", "nosniff").header("Referrer-Policy", "no-referrer").header("X-Frame-Options", "DENY")
                .body(html);
    }

    static String money(long minor, String currency) {
        try {
            NumberFormat f = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
            f.setCurrency(Currency.getInstance(currency));
            return f.format(minor / 100.0);
        } catch (RuntimeException e) {
            return String.format("%d.%02d %s", minor / 100, minor % 100, currency);
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
