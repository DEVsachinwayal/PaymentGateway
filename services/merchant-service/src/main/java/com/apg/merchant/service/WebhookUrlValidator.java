package com.apg.merchant.service;

import com.apg.common.api.ApiException;
import com.apg.merchant.config.MerchantProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for merchant-supplied webhook URLs: https only, no credentials in URL, no localhost / private /
 * link-local IP literals. (Hostnames are resolved and re-checked again at delivery time in M12.)
 */
@Component
public class WebhookUrlValidator {
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private final boolean allowInsecure;

    public WebhookUrlValidator(MerchantProperties props) { this.allowInsecure = props.allowInsecureWebhookUrls(); }

    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw bad("Malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null) throw bad("URL must have a host and no credentials");
        if (allowInsecure) {
            if (!scheme.equals("http") && !scheme.equals("https")) throw bad("Only http/https allowed");
            return;
        }
        if (!scheme.equals("https")) throw bad("Webhook URL must use https");
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".internal") || h.endsWith(".local")) {
            throw bad("Host not allowed");
        }
        boolean literal = IPV4.matcher(h).matches() || h.contains(":");
        if (literal) {
            try {
                InetAddress a = InetAddress.getByName(h.replace("[", "").replace("]", ""));
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                        || a.isMulticastAddress() || a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc) {
                    throw bad("Private or loopback addresses are not allowed");
                }
            } catch (UnknownHostException e) {
                throw bad("Invalid host");
            }
        }
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_URL", msg);
    }
}
