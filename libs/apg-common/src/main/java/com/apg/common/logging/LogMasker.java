package com.apg.common.logging;

import java.util.regex.Pattern;

/**
 * Last line of defence against secrets in logs (spec 19: no credentials, tokens, CVV or full card data in logs).
 * Code must not log secrets in the first place; this masks the shapes that must never appear even by mistake.
 */
public final class LogMasker {
    private LogMasker() {}

    private static final String MASK = "***";

    private static final Pattern[] SECRETS = {
            Pattern.compile("sk_(?:test|live)_[A-Za-z0-9_.\\-]{6,}"),                        // merchant API keys
            Pattern.compile("whsec_[A-Za-z0-9_\\-]{6,}"),                                    // webhook signing secrets
            Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]*"),   // JWTs (access tokens)
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=\\-]{12,}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    };

    /** key=value or "key":"value" pairs whose key names a secret. */
    private static final Pattern KEYED = Pattern.compile(
            "(?i)((?:password|passwd|secret|salt|token|api[_-]?key|authorization|cvv|cvc|pin|signature|hash|otp|refresh[_-]?token|access[_-]?token)"
                    + "[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;&}]{3,})");

    /** 13-19 digit runs (optionally space/dash separated) that pass the Luhn check are card numbers. */
    private static final Pattern CARD = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

    public static String mask(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        for (Pattern p : SECRETS) {
            out = p.matcher(out).replaceAll(m -> m.groupCount() >= 1 && m.group(1) != null ? java.util.regex.Matcher.quoteReplacement(m.group(1)) + MASK : MASK);
        }
        out = KEYED.matcher(out).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)) + MASK);
        var m = CARD.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(luhn(digits) ? MASK : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean luhn(String digits) {
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
