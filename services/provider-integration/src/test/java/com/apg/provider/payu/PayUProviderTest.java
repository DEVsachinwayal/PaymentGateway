package com.apg.provider.payu;

import static org.junit.jupiter.api.Assertions.*;

import com.apg.provider.api.ProviderException;
import com.apg.provider.api.ProviderModels.*;
import com.apg.provider.service.CallLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayUProviderTest {
    FakePayU payu;
    PayUProvider provider;
    final List<String> logged = new ArrayList<>();

    @BeforeEach
    void setUp() {
        payu = new FakePayU();
        CallLog capture = new CallLog(null, Clock.systemUTC()) {
            @Override
            public void record(String p, String op, String ref, String outcome, Integer http, long ms, String detail) {
                logged.add(p + "|" + op + "|" + ref + "|" + outcome + "|" + detail);
            }
        };
        provider = new PayUProvider(new PayUProperties(true, 1, FakePayU.KEY, FakePayU.SALT, payu.baseUrl(),
                Duration.ofSeconds(1), Duration.ofMillis(800)), new ObjectMapper(), capture, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() { payu.close(); }

    // ------------------------------------------------------------------ checkout
    private PaymentRequest req(String desc, String name, String email, String phone, String currency) {
        return new PaymentRequest("A1b2", 1000, currency, desc, name, email, phone, "pay-123",
                "https://apg.example/cb/success", "https://apg.example/cb/failure");
    }

    @Test void checkoutFieldsCarryTheIndependentlyComputedHashAndNeverTheSalt() {
        CheckoutInstructions c = provider.createPayment(req("Test Item", "Asha", "asha@example.com", "+91 98765-43210", "INR"));
        assertEquals("POST", c.method());
        assertEquals(payu.baseUrl() + "/_payment", c.url());
        assertEquals(PayUHasherTest.REQ, c.fields().get("hash"));
        assertEquals("10.00", c.fields().get("amount"));
        assertEquals("919876543210", c.fields().get("phone"));
        assertEquals("pay-123", c.fields().get("udf1"));
        assertEquals("https://apg.example/cb/success", c.fields().get("surl"));
        assertFalse(c.fields().values().contains(FakePayU.SALT), "salt must never be sent to the browser");
        assertTrue(logged.stream().noneMatch(s -> s.contains(FakePayU.SALT) || s.contains(PayUHasherTest.REQ)));
    }

    @Test void checkoutSanitisesFieldsAndAppliesFallbacks() {
        CheckoutInstructions c = provider.createPayment(req("Bad|Info\nline", null, " ", null, "INR"));
        assertEquals("Bad Info line", c.fields().get("productinfo"));
        assertEquals("Customer", c.fields().get("firstname"));
        assertEquals("customer@example.com", c.fields().get("email"));
        assertEquals("", c.fields().get("phone"));
        // hash is computed over the SANITISED values PayU will receive
        assertEquals(PayUHasher.paymentRequestHash(FakePayU.KEY, "A1b2", "10.00", "Bad Info line", "Customer",
                "customer@example.com", new String[]{"pay-123", "", "", "", ""}, FakePayU.SALT), c.fields().get("hash"));
    }

    @Test void onlyInrIsAccepted() {
        ProviderException e = assertThrows(ProviderException.class,
                () -> provider.createPayment(req("x", "a", "a@b.com", null, "USD")));
        assertEquals("UNSUPPORTED_CURRENCY", e.code());
    }

    // ------------------------------------------------------------------ status via verify_payment
    @Test void statusMapping() {
        payu.scenarios.put("T-OK", FakePayU.tx("T-OK", "success", "captured", "10.00"));
        payu.scenarios.put("T-FAIL", FakePayU.tx("T-FAIL", "failure", "failed", "10.00"));
        payu.scenarios.put("T-CANC", FakePayU.tx("T-CANC", "failure", "usercancelled", "10.00"));
        payu.scenarios.put("T-PEND", FakePayU.tx("T-PEND", "pending", "pending", "10.00"));
        payu.scenarios.put("T-AUTH", FakePayU.tx("T-AUTH", "success", "auth", "10.00"));   // authorised, not captured => not success

        PaymentStatusResponse ok = provider.getPaymentStatus("T-OK");
        assertEquals(Status.SUCCESS, ok.status());
        assertEquals(1000L, ok.amountMinor());
        assertEquals("403993715", ok.providerPaymentId());
        assertEquals(Status.FAILED, provider.getPaymentStatus("T-FAIL").status());
        assertEquals(Status.FAILED, provider.getPaymentStatus("T-CANC").status());
        assertEquals(Status.PENDING, provider.getPaymentStatus("T-PEND").status());
        assertEquals(Status.PENDING, provider.getPaymentStatus("T-AUTH").status());
        assertEquals(Status.NOT_FOUND, provider.getPaymentStatus("T-NONE").status());
        // every request really carried a valid hash (the fake rejects bad ones with "Invalid Hash.")
        assertTrue(payu.requests.stream().allMatch(r -> PayUHasher.commandHash(FakePayU.KEY, "verify_payment", r.get("var1"), FakePayU.SALT).equals(r.get("hash"))));
    }

    @Test void providerProblemsBecomeExceptionsNotFailures() {
        payu.scenarios.put("T-RATE", "RATE");
        assertEquals("PROVIDER_RATE_LIMITED", assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-RATE")).code());
        payu.scenarios.put("T-SLOW", "SLOW");
        assertEquals("PROVIDER_UNAVAILABLE", assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-SLOW")).code(),
                "a timeout must surface as UNKNOWN-outcome, never as 'failed'");
        payu.scenarios.put("T-HTML", "<html>maintenance</html>");
        assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-HTML"));
        payu.scenarios.put("T-BADAMT", FakePayU.tx("T-BADAMT", "success", "captured", "10.005"));
        assertEquals("PROVIDER_BAD_RESPONSE", assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-BADAMT")).code());
    }

    @Test void wrongCredentialsAreDetected() {
        PayUProvider wrong = new PayUProvider(new PayUProperties(true, 1, FakePayU.KEY, "wrongsalt", payu.baseUrl(),
                Duration.ofSeconds(1), Duration.ofSeconds(1)), new ObjectMapper(), new CallLog(null, Clock.systemUTC()) {
            @Override public void record(String p, String op, String r, String o, Integer h, long m, String d) { }
        }, Clock.systemUTC());
        assertThrows(ProviderException.class, () -> wrong.getPaymentStatus("T-OK"));   // "Invalid Hash." has no transaction block
    }

    @Test void circuitOpensAfterRepeatedFailuresAndStopsHammeringTheProvider() {
        payu.scenarios.put("T-RATE", "RATE");
        for (int i = 0; i < 5; i++) assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-RATE"));
        int before = payu.hits.get();
        ProviderException e = assertThrows(ProviderException.class, () -> provider.getPaymentStatus("T-RATE"));
        assertEquals("PROVIDER_UNAVAILABLE", e.code());
        assertEquals(before, payu.hits.get(), "an open circuit must not call PayU (it rate-limits bad callers)");
        assertFalse(provider.health().up());
    }

    // ------------------------------------------------------------------ refund
    @Test void refundAcceptedRejectedAndUnknown() {
        RefundResponse ok = provider.refund(new RefundRequest("403993715", "RF0001abc", 1000));
        assertEquals(RefundStatus.ACCEPTED, ok.status());
        assertEquals("77001", ok.providerRequestId());
        Map<String, String> sent = payu.requests.get(payu.requests.size() - 1);
        assertEquals("cancel_refund_transaction", sent.get("command"));
        assertEquals("403993715", sent.get("var1"));
        assertEquals("RF0001abc", sent.get("var2"));
        assertEquals("10.00", sent.get("var3"));

        payu.scenarios.put("refund:RF0002abc", "{\"status\":0,\"msg\":\"Token already used\"}");
        RefundResponse rej = provider.refund(new RefundRequest("403993715", "RF0002abc", 500));
        assertEquals(RefundStatus.REJECTED, rej.status());
        assertEquals("Token already used", rej.message());

        payu.scenarios.put("refund:RF0003abc", "<html>boom</html>");
        assertEquals(RefundStatus.UNKNOWN, provider.refund(new RefundRequest("403993715", "RF0003abc", 500)).status(),
                "unclear refund result must be UNKNOWN (verify, don't retry blindly)");
    }

    @Test void refundStatusIsSuccessOnlyWhenPayUSaysSo() {
        RefundStatusResponse queued = provider.getRefundStatus(new RefundStatusRequest("77001", "RF0001abc"));
        assertEquals(RefundOutcome.PROCESSING, queued.outcome(), "queued is not success");

        payu.scenarios.put("refundstatus:77002", "{\"status\":1,\"msg\":\"ok\",\"transaction_details\":{\"403993715\":{\"77002\":"
                + "{\"request_id\":\"77002\",\"token\":\"RF0002abc\",\"status\":\"success\"}}}}");
        assertEquals(RefundOutcome.SUCCESS, provider.getRefundStatus(new RefundStatusRequest("77002", "RF0002abc")).outcome());

        payu.scenarios.put("refundstatus:77003", "{\"status\":1,\"msg\":\"ok\",\"transaction_details\":{\"x\":{\"request_id\":\"77003\",\"status\":\"failed\"}}}");
        assertEquals(RefundOutcome.FAILED, provider.getRefundStatus(new RefundStatusRequest("77003", "RF0003abc")).outcome());

        payu.scenarios.put("refundstatus:77004", "{\"status\":1,\"msg\":\"ok\",\"transaction_details\":{}}");
        assertEquals(RefundOutcome.PROCESSING, provider.getRefundStatus(new RefundStatusRequest("77004", "RF0004abc")).outcome(),
                "unknown shape must never be treated as success");

        payu.scenarios.put("refundstatus:77005", "<html>boom</html>");
        assertThrows(ProviderException.class, () -> provider.getRefundStatus(new RefundStatusRequest("77005", "RF0005abc")));
    }

    // ------------------------------------------------------------------ callback verification
    private Map<String, String> callback(String hash) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("key", "testkey"); f.put("txnid", "A1b2"); f.put("amount", "10.00"); f.put("productinfo", "Test Item");
        f.put("firstname", "Asha"); f.put("email", "asha@example.com"); f.put("udf1", "pay-123");
        f.put("status", "success"); f.put("unmappedstatus", "captured"); f.put("mihpayid", "403993715"); f.put("hash", hash);
        return f;
    }

    @Test void genuineCallbackIsAuthentic() {
        ProviderWebhookResult r = provider.verifyWebhook(new ProviderWebhookRequest(callback(PayUHasherTest.RESP)));
        assertTrue(r.authentic(), r.reason());
        assertEquals(Status.SUCCESS, r.status());
        assertEquals("A1b2", r.providerReference());
        assertEquals(1000L, r.amountMinor());
        assertEquals("403993715", r.providerPaymentId());
    }

    @Test void callbackWithAdditionalChargesNeedsItsOwnHash() {
        Map<String, String> f = callback(PayUHasherTest.RESP_ADDITIONAL);
        f.put("additionalCharges", "1.50");
        assertTrue(provider.verifyWebhook(new ProviderWebhookRequest(f)).authentic());
        f.remove("additionalCharges");                       // attacker strips the field => hash no longer matches
        assertFalse(provider.verifyWebhook(new ProviderWebhookRequest(f)).authentic());
    }

    @Test void tamperedOrIncompleteCallbacksAreRejected() {
        for (String field : new String[]{"amount", "status", "txnid", "email", "udf1", "firstname", "productinfo"}) {
            Map<String, String> f = callback(PayUHasherTest.RESP);
            f.put(field, f.get(field) + "x");
            ProviderWebhookResult r = provider.verifyWebhook(new ProviderWebhookRequest(f));
            assertFalse(r.authentic(), "tampering with " + field + " must be detected");
            assertEquals(Status.UNKNOWN, r.status());
        }
        Map<String, String> wrongKey = callback(PayUHasherTest.RESP);
        wrongKey.put("key", "otherkey");
        assertFalse(provider.verifyWebhook(new ProviderWebhookRequest(wrongKey)).authentic());

        for (String missing : new String[]{"hash", "txnid", "status", "amount", "key"}) {
            Map<String, String> f = callback(PayUHasherTest.RESP);
            f.remove(missing);
            assertFalse(provider.verifyWebhook(new ProviderWebhookRequest(f)).authentic(), "missing " + missing);
        }
        Map<String, String> junk = callback("not-a-hash");
        assertFalse(provider.verifyWebhook(new ProviderWebhookRequest(junk)).authentic());
        Map<String, String> badAmount = callback(PayUHasherTest.RESP);
        assertTrue(logged.stream().anyMatch(s -> s.contains("INVALID")));
        assertTrue(logged.stream().noneMatch(s -> s.contains(FakePayU.SALT)));
    }

    // ------------------------------------------------------------------ helpers
    @Test void moneyConversionsAreExact() {
        assertEquals("0.01", PayUProvider.toMajor(1));
        assertEquals("499.00", PayUProvider.toMajor(49900));
        assertEquals("1234567.89", PayUProvider.toMajor(123456789));
        assertEquals(1050L, PayUProvider.toMinor("10.5"));
        assertEquals(1000L, PayUProvider.toMinor("10.00"));
        assertThrows(ArithmeticException.class, () -> PayUProvider.toMinor("10.005"));
    }

    @Test void normalizeNeverTurnsAmbiguousIntoFailed() {
        assertEquals(Status.SUCCESS, PayUProvider.normalize("success", "captured"));
        assertEquals(Status.FAILED, PayUProvider.normalize("failure", "failed"));
        assertEquals(Status.FAILED, PayUProvider.normalize("failure", "usercancelled"));
        for (String u : new String[]{"pending", "dropped", "bounced", "auth", "", null}) {
            assertEquals(Status.PENDING, PayUProvider.normalize("pending", u), "pending/" + u);
        }
        assertEquals(Status.PENDING, PayUProvider.normalize("success", "auth"));
    }
}
