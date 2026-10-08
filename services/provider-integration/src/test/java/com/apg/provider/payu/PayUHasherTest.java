package com.apg.provider.payu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Expected values were computed independently with `openssl dgst -sha512` from the formulas in PayU's docs
 * (key=testkey, salt=testsalt, txnid=A1b2, amount=10.00, productinfo="Test Item", firstname=Asha,
 * email=asha@example.com, udf1=pay-123), so the production code is checked against something other than itself.
 */
class PayUHasherTest {
    static final String REQ = "818d6c4e60854a6eaa0343a7d6a113b36965435b7fe52e64daf641525af855ebf6b7115097699ff5e04de1c71c070ba69c636f03deb2a93963b5d9c01200e6e6";
    static final String RESP = "4fd683ffb6de504fc31b4a3436a18f2e488bd997c3b697dbc44d54e1ed9b75350924cda6dc9063cbe399dd351aaf982cbcf607f292199c35e56f691258061c38";
    static final String RESP_ADDITIONAL = "a8a26d6d6785b2fc9abbcbc5db73cd09b6ccf9b77d294a6b3af2a51d12adc460c70e8be4a5407f25772545d14c37bd7b34f9f9eb723947d92b6f4cddeb88ad94";
    static final String VERIFY = "0514c3c1e76acc3fb1b584321d798c7e9bba89c87888687ec811c4a1f78e2f0f03f00f9583268d01891bca28c3156a007cc200ad2dcfbdd80a7bcfd562e5cffe";
    static final String REFUND = "299e100adfc7dbaf03fc020b68df709b173c1b9b9bf2d1699d0c8e55b9fa306b17e95fc150f4f55d34c6368b8e0af7dda7a15f310768c8f06e57029a266f1453";

    private static final String[] UDF = {"pay-123", "", "", "", ""};

    @Test void paymentRequestHashMatchesIndependentVector() {
        assertEquals(REQ, PayUHasher.paymentRequestHash("testkey", "A1b2", "10.00", "Test Item", "Asha",
                "asha@example.com", UDF, "testsalt"));
    }

    @Test void responseHashMatchesIndependentVectors() {
        assertEquals(RESP, PayUHasher.responseHash(null, "testsalt", "success", UDF, "asha@example.com", "Asha",
                "Test Item", "10.00", "A1b2", "testkey"));
        assertEquals(RESP_ADDITIONAL, PayUHasher.responseHash("1.50", "testsalt", "success", UDF, "asha@example.com", "Asha",
                "Test Item", "10.00", "A1b2", "testkey"));
        assertNotEquals(RESP, RESP_ADDITIONAL);
    }

    @Test void commandHashesMatchIndependentVectors() {
        assertEquals(VERIFY, PayUHasher.commandHash("testkey", "verify_payment", "A1b2", "testsalt"));
        assertEquals(REFUND, PayUHasher.commandHash("testkey", "cancel_refund_transaction", "403993715", "testsalt"));
    }

    @Test void anyChangedFieldChangesTheHash() {
        String base = PayUHasher.paymentRequestHash("testkey", "A1b2", "10.00", "Test Item", "Asha", "asha@example.com", UDF, "testsalt");
        assertNotEquals(base, PayUHasher.paymentRequestHash("testkey", "A1b2", "10.01", "Test Item", "Asha", "asha@example.com", UDF, "testsalt"));
        assertNotEquals(base, PayUHasher.paymentRequestHash("testkey", "A1b3", "10.00", "Test Item", "Asha", "asha@example.com", UDF, "testsalt"));
        assertNotEquals(base, PayUHasher.paymentRequestHash("testkey", "A1b2", "10.00", "Test Item", "Asha", "asha@example.com", UDF, "othersalt"));
    }

    @Test void comparisonIsCaseInsensitiveAndNullSafe() {
        assertTrue(PayUHasher.constantTimeEqualsIgnoreCase("ABCDEF", "abcdef"));
        assertFalse(PayUHasher.constantTimeEqualsIgnoreCase("abc", "abd"));
        assertFalse(PayUHasher.constantTimeEqualsIgnoreCase(null, "abc"));
        assertFalse(PayUHasher.constantTimeEqualsIgnoreCase("abc", null));
    }
}
