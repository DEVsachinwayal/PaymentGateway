package com.apg.settlement;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.NestedRuntimeException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.settlement.rerun.enabled=false", "apg.settlement.max-items-per-batch=50"})
@AutoConfigureMockMvc
class SettlementFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";
    static final FakePeers peers = new FakePeers();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("apg.settlement.payment-service-uri", peers::baseUrl);
        r.add("apg.settlement.refund-service-uri", peers::baseUrl);
    }

    @AfterAll
    static void stop() { peers.close(); }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    private String uniq() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

    private String item(String type, String ref, long amount, long fee, long tax) {
        return "{\"type\":\"" + type + "\",\"providerReference\":\"" + ref + "\",\"amount\":" + amount + ",\"fee\":" + fee + ",\"tax\":" + tax + "}";
    }

    private String batch(String ref, long gross, long refunds, long fees, long net, String... items) {
        return "{\"provider\":\"payu\",\"batchRef\":\"" + ref + "\",\"settlementDate\":\"2026-10-08\",\"currency\":\"INR\",\"declaredGross\":" + gross
                + ",\"declaredRefunds\":" + refunds + ",\"declaredFees\":" + fees + ",\"declaredNet\":" + net + ",\"importedBy\":\"ops@apg\",\"items\":[" + String.join(",", items) + "]}";
    }

    private ResultActions imp(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/settlements/import").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID importOk(String json) throws Exception {
        String res = imp(json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(res).path("data").path("id").asText());
    }

    private String batchStatus(UUID id) { return jdbc.queryForObject("select status from settlements where id = ?", String.class, id); }

    private int events(UUID id, String type) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? and event_type = ?", Integer.class, id, type);
    }

    private ResultActions reconcile(UUID id) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/settlements/" + id + "/reconcile").header("X-Internal-Token", TOKEN));
    }

    private List<String> openExceptionTypes(UUID id) {
        return jdbc.queryForList("select exc_type from reconciliation_exceptions where settlement_id = ? and status in ('OPEN','INVESTIGATING') order by exc_type", String.class, id);
    }

    // ------------------------------------------------------------------ clean reconciliation

    @Test void cleanBatchReconcilesAndCreatesMerchantShares() throws Exception {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID();
        String t1 = "T" + uniq(), t2 = "T" + uniq(), t3 = "T" + uniq(), r1 = "RF" + uniq();
        peers.payment(m1, t1, 10000, "SUCCESS");
        peers.payment(m1, t2, 5000, "PARTIALLY_REFUNDED");
        peers.payment(m2, t3, 7000, "SUCCESS");
        peers.refund(m1, r1, 2000, "SUCCESS");
        // fees: 236 + 118 + 165 = 519 (fee+tax); net = 9764 + 4882 + 6835 - 2000 = 19481
        UUID id = importOk(batch("B-" + uniq(), 22000, 2000, 519, 19481,
                item("PAYMENT", t1, 10000, 200, 36), item("PAYMENT", t2, 5000, 100, 18), item("PAYMENT", t3, 7000, 140, 25), item("REFUND", r1, 2000, 0, 0)));

        assertEquals("RECONCILED", batchStatus(id));
        assertEquals(4, jdbc.queryForObject("select count(*) from settlement_items where settlement_id = ? and match_status = 'MATCHED'", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from reconciliation_exceptions where settlement_id = ?", Integer.class, id));
        assertEquals(1, events(id, "settlement.created"));

        // merchant 1: gross 15000, refunds 2000, fees 354 => net 12646
        var ms = jdbc.queryForMap("select gross, refunds, provider_fees, net, status from merchant_settlements where settlement_id = ? and merchant_id = ?", id, m1);
        assertEquals(15000L, ms.get("gross")); assertEquals(2000L, ms.get("refunds")); assertEquals(354L, ms.get("provider_fees"));
        assertEquals(12646L, ms.get("net")); assertEquals("CALCULATED", ms.get("status"));
        assertEquals(6835L, jdbc.queryForObject("select net from merchant_settlements where settlement_id = ? and merchant_id = ?", Long.class, id, m2));

        JsonNode env = mapper.readTree(jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? and event_type = 'settlement.created'", String.class, id));
        assertEquals(19481, env.path("payload").path("amount").asLong());
        assertEquals(519, env.path("payload").path("providerFees").asLong());
        assertEquals("payu", env.path("payload").path("provider").asText());

        // rerun is harmless: no second event, no second merchant rows
        reconcile(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RECONCILED"));
        assertEquals(1, events(id, "settlement.created"));
        assertEquals(2, jdbc.queryForObject("select count(*) from merchant_settlements where settlement_id = ?", Integer.class, id));
    }

    // ------------------------------------------------------------------ findings

    @Test void everyKindOfDifferenceBecomesAnExceptionAndNothingIsSettled() throws Exception {
        UUID m = UUID.randomUUID();
        String amt = "T" + uniq(), unk = "T" + uniq(), st = "T" + uniq(), ref = "T" + uniq(), okTx = "T" + uniq();
        String rUnk = "RF" + uniq(), rSt = "RF" + uniq(), rAmt = "RF" + uniq();
        peers.payment(m, amt, 1000, "SUCCESS");
        peers.payment(m, st, 1000, "FAILED");
        peers.payment(m, okTx, 1000, "SUCCESS");
        FakePeers.Payment other = peers.payment(m, ref, 1000, "SUCCESS");
        peers.refund(m, rSt, 300, "PROCESSING");
        peers.refund(m, rAmt, 300, "SUCCESS");
        // item "ref" carries a provider payment id that APG does not have
        String refItem = "{\"type\":\"PAYMENT\",\"providerReference\":\"" + ref + "\",\"providerPaymentId\":\"WRONG\",\"amount\":1000}";
        long gross = 5000, refunds = 900;
        UUID id = importOk(batch("B-" + uniq(), gross, refunds, 0, gross - refunds,
                item("PAYMENT", amt, 1100, 0, 0), item("PAYMENT", unk, 1000, 0, 0), item("PAYMENT", st, 1000, 0, 0),
                refItem, item("PAYMENT", okTx, 1000, 0, 0), item("REFUND", rUnk, 300, 0, 0), item("REFUND", rSt, 300, 0, 0), item("REFUND", rAmt, 350, 0, 0)));
        // declared totals above were deliberately sloppy; the point of this test is the per-item findings
        assertEquals("HAS_EXCEPTIONS", batchStatus(id));
        List<String> types = jdbc.queryForList("""
                select e.exc_type || ':' || i.provider_reference from reconciliation_exceptions e join settlement_items i on i.id = e.item_id
                where e.settlement_id = ? order by e.exc_type, i.line_no""", String.class, id);
        assertEquals(List.of("AMOUNT_MISMATCH:" + amt, "AMOUNT_MISMATCH:" + rAmt, "REFERENCE_MISMATCH:" + ref, "STATUS_MISMATCH:" + st,
                "STATUS_MISMATCH:" + rSt, "UNKNOWN_PAYMENT:" + unk, "UNKNOWN_REFUND:" + rUnk), types);
        assertTrue(openExceptionTypes(id).contains("TOTAL_MISMATCH"), "declared totals do not match the items");
        assertEquals(1, jdbc.queryForObject("select count(*) from settlement_items where settlement_id = ? and match_status = 'MATCHED'", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("select count(*) from merchant_settlements where settlement_id = ?", Integer.class, id));
        assertEquals(0, events(id, "settlement.created"), "a batch with findings must not be announced to the ledger");
        assertTrue(events(id, "reconciliation.exception") >= 8);

        String evidence = jdbc.queryForObject("select e.evidence from reconciliation_exceptions e join settlement_items i on i.id = e.item_id where e.exc_type = 'AMOUNT_MISMATCH' and i.provider_reference = ?", String.class, amt);
        JsonNode ev = mapper.readTree(evidence);
        assertEquals(1100, ev.path("providerAmount").asLong());
        assertEquals(1000, ev.path("apgAmount").asLong());

        // rerun does not duplicate findings
        int before = jdbc.queryForObject("select count(*) from reconciliation_exceptions where settlement_id = ?", Integer.class, id);
        reconcile(id).andExpect(status().isOk());
        assertEquals(before, jdbc.queryForObject("select count(*) from reconciliation_exceptions where settlement_id = ?", Integer.class, id));
        assertNotNull(other);
    }

    @Test void aFindingClosesItselfWhenApgCatchesUp() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 4000, "VERIFY_REQUIRED");
        UUID id = importOk(batch("B-" + uniq(), 4000, 0, 0, 4000, item("PAYMENT", tx, 4000, 0, 0)));
        assertEquals("HAS_EXCEPTIONS", batchStatus(id));
        assertEquals(List.of("STATUS_MISMATCH"), openExceptionTypes(id));

        peers.payment(m, tx, 4000, "SUCCESS");      // APG's verification finished
        reconcile(id).andExpect(jsonPath("$.data.status").value("RECONCILED"));
        assertEquals("RESOLVED", jdbc.queryForObject("select status from reconciliation_exceptions where settlement_id = ?", String.class, id));
        assertNotNull(jdbc.queryForObject("select resolution_notes from reconciliation_exceptions where settlement_id = ?", String.class, id));
        assertEquals(1, events(id, "settlement.created"));
    }

    @Test void duplicateSettlementOfTheSameTransactionIsCaught() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 1000, "SUCCESS");
        // twice in one batch
        UUID a = importOk(batch("B-" + uniq(), 2000, 0, 0, 2000, item("PAYMENT", tx, 1000, 0, 0), item("PAYMENT", tx, 1000, 0, 0)));
        assertEquals(List.of("DUPLICATE_ITEM"), openExceptionTypes(a));
        assertEquals(1, jdbc.queryForObject("select count(*) from settlement_items where settlement_id = ? and match_status = 'MATCHED'", Integer.class, a));
        // and again in a later batch
        String tx2 = "T" + uniq();
        peers.payment(m, tx2, 500, "SUCCESS");
        UUID b1 = importOk(batch("B-" + uniq(), 500, 0, 0, 500, item("PAYMENT", tx2, 500, 0, 0)));
        assertEquals("RECONCILED", batchStatus(b1));
        UUID b2 = importOk(batch("B-" + uniq(), 500, 0, 0, 500, item("PAYMENT", tx2, 500, 0, 0)));
        assertEquals(List.of("DUPLICATE_ITEM"), openExceptionTypes(b2));
    }

    @Test void adjustmentsNeedAHumanAndTheWorkflowIsEnforced() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 3000, "SUCCESS");
        UUID id = importOk(batch("B-" + uniq(), 3000, 0, 0, 2500, item("PAYMENT", tx, 3000, 0, 0),
                "{\"type\":\"ADJUSTMENT\",\"providerReference\":\"CHARGEBACK-1\",\"net\":-500}"));
        assertEquals("HAS_EXCEPTIONS", batchStatus(id));
        UUID exc = jdbc.queryForObject("select id from reconciliation_exceptions where settlement_id = ? and exc_type = 'ADJUSTMENT_REVIEW'", UUID.class, id);

        patch(exc, "{\"status\":\"RESOLVED\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("NOTES_REQUIRED"));
        patch(exc, "{\"status\":\"INVESTIGATING\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("OWNER_REQUIRED"));
        patch(exc, "{\"status\":\"BANANA\",\"notes\":\"x\"}").andExpect(status().isBadRequest());
        patch(exc, "{\"status\":\"INVESTIGATING\",\"owner\":\"finance-1\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INVESTIGATING"));
        assertEquals("HAS_EXCEPTIONS", batchStatus(id));
        patch(exc, "{\"status\":\"RESOLVED\",\"notes\":\"Chargeback CB-1 accepted, deducted from payout\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED")).andExpect(jsonPath("$.data.evidence").isNotEmpty());
        assertEquals("RECONCILED", batchStatus(id), "an acknowledged adjustment completes the batch");
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject("select match_status from settlement_items where settlement_id = ? and item_type = 'ADJUSTMENT'", String.class, id));
        patch(exc, "{\"status\":\"WRITTEN_OFF\",\"notes\":\"again\"}").andExpect(status().isConflict());
        reconcile(id).andExpect(jsonPath("$.data.status").value("RECONCILED"));
        assertEquals("RESOLVED", jdbc.queryForObject("select status from reconciliation_exceptions where id = ?", String.class, exc), "a rerun never reopens a human decision");
    }

    private ResultActions patch(UUID id, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch("/internal/v1/exceptions/" + id).header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ------------------------------------------------------------------ dependencies

    @Test void anUnavailableLookupIsNotAPassAndNotAFinding() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 1500, "SUCCESS");
        peers.down = true;
        UUID id;
        try {
            id = importOk(batch("B-" + uniq(), 1500, 0, 0, 1500, item("PAYMENT", tx, 1500, 0, 0)));
        } finally {
            peers.down = false;
        }
        assertEquals("IMPORTED", batchStatus(id), "not reconciled, not flagged: simply not evaluated yet");
        assertEquals(0, jdbc.queryForObject("select count(*) from reconciliation_exceptions where settlement_id = ?", Integer.class, id));
        assertEquals(1, jdbc.queryForObject("select lookup_errors from reconciliation_runs where settlement_id = ?", Integer.class, id));
        assertEquals(0, events(id, "settlement.created"));
        reconcile(id).andExpect(jsonPath("$.data.status").value("RECONCILED"));
        assertEquals(1, events(id, "settlement.created"));
    }

    // ------------------------------------------------------------------ evidence

    @Test void importIsIdempotentAndEvidenceIsNeverOverwritten() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 800, "SUCCESS");
        String ref = "B-" + uniq();
        String json = batch(ref, 800, 0, 0, 800, item("PAYMENT", tx, 800, 0, 0));
        UUID id = importOk(json);
        imp(json.replace("ops@apg", "someone-else")).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString()));
        imp(batch(ref, 900, 0, 0, 900, item("PAYMENT", tx, 900, 0, 0))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("BATCH_CONFLICT"));
        assertEquals(1, jdbc.queryForObject("select count(*) from settlements where batch_ref = ?", Integer.class, ref));
        assertEquals(800L, jdbc.queryForObject("select declared_gross from settlements where id = ?", Long.class, id));
    }

    @Test void databaseRefusesToEditOrDeleteEvidence() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 800, "SUCCESS");
        UUID id = importOk(batch("B-" + uniq(), 800, 0, 0, 800, item("PAYMENT", tx, 800, 0, 0)));
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("update settlements set declared_net = 1 where id = ?", id));
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("update settlements set evidence = 'x' where id = ?", id));
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("update settlement_items set amount = 1 where settlement_id = ?", id));
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("delete from settlement_items where settlement_id = ?", id));
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("delete from settlements where id = ?", id));
        assertEquals(1, jdbc.update("update settlements set status = status where id = ?", id), "workflow columns stay updatable");
    }

    @Test void inputIsValidated() throws Exception {
        String ok = item("PAYMENT", "T1", 100, 0, 0);
        imp(batch("V1", 100, 0, 0, 100, item("PAYMENT", "T1", 100, 90, 20))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_ITEM"));
        imp(batch("V2", 100, 0, 0, 100, "{\"type\":\"PAYMENT\",\"amount\":100}")).andExpect(status().isBadRequest());
        imp(batch("V3", 100, 0, 0, 100, "{\"type\":\"PAYMENT\",\"providerReference\":\"T\",\"amount\":100,\"net\":5}")).andExpect(status().isBadRequest());
        imp(batch("V4", 100, 0, 0, 100, "{\"type\":\"REFUND\",\"providerReference\":\"R\",\"amount\":100,\"net\":100}")).andExpect(status().isBadRequest());
        imp(batch("V5", 100, 0, 0, 100, "{\"type\":\"ADJUSTMENT\",\"net\":0}")).andExpect(status().isBadRequest());
        imp(batch("V6", 100, 0, 0, 100, "{\"type\":\"GIFT\",\"providerReference\":\"T\",\"amount\":100}")).andExpect(status().isBadRequest());
        imp(batch("V7", 100, 0, 0, 100, item("PAYMENT", "T1", -5, 0, 0))).andExpect(status().isBadRequest());
        imp(batch("V8", 100, 0, 0, 100, "{\"type\":\"PAYMENT\",\"providerReference\":\"T\",\"amount\":10.5}")).andExpect(status().isBadRequest());
        imp(batch("V9", 100, 0, 0, 100, "{\"type\":\"PAYMENT\",\"providerReference\":\"T\",\"amount\":100,\"surprise\":1}")).andExpect(status().isBadRequest());
        imp(batch("V10", 100, 0, 0, 100)).andExpect(status().isBadRequest());                        // no items
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add(ok);
        imp(batch("V11", 5100, 0, 0, 5100, many.toArray(new String[0]))).andExpect(status().isPayloadTooLarge());
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/settlements/import").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("select count(*) from settlements where batch_ref like 'V%'", Integer.class));
    }

    @Test void operatorActionsAreAuditedBeforeAndAfter() throws Exception {
        UUID m = UUID.randomUUID();
        String tx = "T" + uniq();
        peers.payment(m, tx, 700, "SUCCESS");
        String ref = "B-" + uniq();
        UUID id = importOk(batch(ref, 700, 0, 0, 700, item("PAYMENT", tx, 700, 0, 0)));
        assertEquals(List.of("ATTEMPT", "SUCCESS"), jdbc.queryForList("select outcome from audit_events where action = 'SETTLEMENT_IMPORT' and target = ? order by occurred_at, id", String.class, "payu/" + ref));
        assertEquals("ops:ops@apg", jdbc.queryForObject("select actor from audit_events where action = 'SETTLEMENT_IMPORT' and target = ? limit 1", String.class, "payu/" + ref));
        UUID ms = jdbc.queryForObject("select id from merchant_settlements where settlement_id = ?", UUID.class, id);
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/merchant-settlements/" + ms + "/paid").header("X-Internal-Token", TOKEN).header("X-Operator", "finance-2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"payoutReference\":\"UTR-AUDIT-1\"}")).andExpect(status().isOk());
        assertEquals(List.of("ATTEMPT", "SUCCESS"), jdbc.queryForList("select outcome from audit_events where action = 'PAYOUT_MARK_PAID' and target = ? order by occurred_at, id", String.class, ms.toString()));
        assertEquals(m, jdbc.queryForObject("select merchant_id from audit_events where action = 'PAYOUT_MARK_PAID' and outcome = 'SUCCESS' and target = ?", UUID.class, ms.toString()));
    }

    // ------------------------------------------------------------------ payouts and merchant view

    @Test void payoutNeedsEvidenceAndMerchantsSeeOnlyTheirOwn() throws Exception {
        UUID m1 = UUID.randomUUID(), m2 = UUID.randomUUID();
        String t1 = "T" + uniq(), t2 = "T" + uniq();
        peers.payment(m1, t1, 6000, "SUCCESS");
        peers.payment(m2, t2, 4000, "SUCCESS");
        UUID id = importOk(batch("B-" + uniq(), 10000, 0, 0, 10000, item("PAYMENT", t1, 6000, 0, 0), item("PAYMENT", t2, 4000, 0, 0)));
        UUID msId = jdbc.queryForObject("select id from merchant_settlements where settlement_id = ? and merchant_id = ?", UUID.class, id, m1);

        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/merchant-settlements/" + msId + "/paid").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYOUT_REFERENCE_REQUIRED"));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/merchant-settlements/" + msId + "/paid").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payoutReference\":\"UTR123456\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID")).andExpect(jsonPath("$.data.payoutReference").value("UTR123456"));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/merchant-settlements/" + msId + "/paid").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"payoutReference\":\"UTR2\"}")).andExpect(status().isConflict());

        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/settlements"), m1, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1)).andExpect(jsonPath("$.data.content[0].net").value(6000))
                .andExpect(jsonPath("$.data.content[0].status").value("PAID"));
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/settlements/" + msId), m2, "MERCHANT_ADMIN")).andExpect(status().isNotFound());
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/settlements/" + msId), m1, "MERCHANT_DEVELOPER")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/settlements").header("X-Internal-Token", TOKEN).header("X-Auth-Type", "API_KEY")
                .header("X-Merchant-Id", m1.toString())).andExpect(status().isForbidden());
        mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/settlements"), m1, "APG_FINANCE")).andExpect(status().isForbidden());
        // internal evidence never leaks into the merchant view
        String body = mvc.perform(user(MockMvcRequestBuilders.get("/api/v1/settlements"), m1, "MERCHANT_ADMIN")).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("evidence") || body.contains(t2), body);
        assertThrows(NestedRuntimeException.class, () -> jdbc.update("update merchant_settlements set payout_reference = null where id = ?", msId),
                "a PAID settlement without payout evidence must be impossible");
    }

    private MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder b, UUID merchant, String roles) {
        return b.header("X-Internal-Token", TOKEN).header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", roles).header("X-Merchant-Id", merchant.toString());
    }
}
