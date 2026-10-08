package com.apg.provider.payu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PayU SHA-512 formulas exactly as documented at docs.payu.in (see docs/PAYU_INTEGRATION.md).
 * Server side only: the salt must never reach the browser.
 */
final class PayUHasher {
    private PayUHasher() {}

    /** sha512(key|txnid|amount|productinfo|firstname|email|udf1|udf2|udf3|udf4|udf5||||||SALT) */
    static String paymentRequestHash(String key, String txnid, String amount, String productinfo, String firstname,
                                     String email, String[] udf, String salt) {
        StringBuilder sb = new StringBuilder();
        sb.append(key).append('|').append(txnid).append('|').append(amount).append('|').append(productinfo).append('|')
          .append(firstname).append('|').append(email).append('|');
        for (int i = 0; i < 5; i++) sb.append(i < udf.length && udf[i] != null ? udf[i] : "").append('|');
        sb.append("|||||").append(salt);   // udf5's trailing pipe above + these five = six pipes before SALT
        return sha512(sb.toString());
    }

    /**
     * Reverse hash for callbacks:
     * sha512([additionalCharges|]SALT|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)
     */
    static String responseHash(String additionalCharges, String salt, String status, String[] udf, String email,
                               String firstname, String productinfo, String amount, String txnid, String key) {
        StringBuilder sb = new StringBuilder();
        if (additionalCharges != null && !additionalCharges.isEmpty()) sb.append(additionalCharges).append('|');
        sb.append(salt).append('|').append(status).append("||||||");
        for (int i = 4; i >= 0; i--) sb.append(i < udf.length && udf[i] != null ? udf[i] : "").append('|');
        sb.append(email).append('|').append(firstname).append('|').append(productinfo).append('|').append(amount)
          .append('|').append(txnid).append('|').append(key);
        return sha512(sb.toString());
    }

    /** sha512(key|command|var1|SALT) used by verify_payment and cancel_refund_transaction. */
    static String commandHash(String key, String command, String var1, String salt) {
        return sha512(key + '|' + command + '|' + var1 + '|' + salt);
    }

    static boolean constantTimeEqualsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.toLowerCase().getBytes(StandardCharsets.UTF_8), b.toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    static String sha512(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
