package com.apg.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.apg.ledger.service.LedgerProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {"POSTGRES_USER=ignored", "POSTGRES_PASSWORD=ignored", "INTERNAL_SERVICE_TOKEN=internal-token-for-tests-123",
        "apg.ledger.fee.percent-bps=250", "apg.ledger.fee.fixed-minor=3"})
@AutoConfigureMockMvc
class LedgerFlowTest {
    static final String TOKEN = "internal-token-for-tests-123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper mapper;

    private final UUID merchant = UUID.randomUUID();

    private String event(String type, UUID eventId, UUID aggregate, UUID merchantId, Object amount, String currency) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type + "\",\"version\":1,\"occurredAt\":\"2026-10-09T10:00:00Z\","
                + "\"aggregateId\":\"" + aggregate + "\",\"merchantId\":\"" + merchantId + "\",\"payload\":{\"amount\":" + amount
                + ",\"currency\":\"" + currency + "\",\"provider\":\"payu\",\"paymentId\":\"" + aggregate + "\"}}";
    }

    private ResultActions deliver(String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/events").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID capture(UUID m, long amount) throws Exception {
        UUID payment = UUID.randomUUID();
        String res = deliver(event("payment.success", UUID.randomUUID(), payment, m, amount, "INR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("POSTED")).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(res).path("data").path("transactionId").asText());
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder b, UUID m, String roles) {
        return b.header("X-Internal-Token", TOKEN).header("X-Auth-Type", "USER").header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Roles", roles).header("X-Merchant-Id", m.toString());
    }

    // ------------------------------------------------------------------ postings

    @Test void captureIsBalancedAndSplitsTheFee() throws Exception {
        UUID payment = UUID.randomUUID();
        deliver(event("payment.success", UUID.randomUUID(), payment, merchant, 10000, "INR")).andExpect(status().isOk());
        List<String> rows = jdbc.queryForList("""
                SELECT a.code || ':' || e.side || ':' || e.amount FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.reference_id = ? ORDER BY e.id""", String.class, payment);
        // fee = 10000 * 2.5% + 3 = 253
        assertEquals(List.of("PROVIDER_RECEIVABLE_PAYU:DEBIT:10000", "MERCHANT_PAYABLE:CREDIT:9747", "APG_FEE_REVENUE:CREDIT:253"), rows);

        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/balance"), merchant, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].currency").value("INR")).andExpect(jsonPath("$.data[0].payable").value(9747))
                .andExpect(jsonPath("$.data[0].captured").value(10000)).andExpect(jsonPath("$.data[0].fees").value(253))
                .andExpect(jsonPath("$.data[0].refunded").value(0)).andExpect(jsonPath("$.data[0].adjustments").value(0));
    }

    @Test void refundReducesWhatIsOwedToTheMerchant() throws Exception {
        UUID m = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        deliver(event("payment.success", UUID.randomUUID(), payment, m, 20000, "INR")).andExpect(status().isOk());
        UUID refund = UUID.randomUUID();
        deliver(event("refund.success", UUID.randomUUID(), refund, m, 5000, "INR")).andExpect(jsonPath("$.data.outcome").value("POSTED"));
        // fee 503 on the payment, 19497 payable, minus the 5000 refund
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/balance"), m, "MERCHANT_DEVELOPER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payable").value(14497)).andExpect(jsonPath("$.data[0].refunded").value(5000))
                .andExpect(jsonPath("$.data[0].captured").value(20000)).andExpect(jsonPath("$.data[0].adjustments").value(0));
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/transactions"), m, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2)).andExpect(jsonPath("$.data.content[0].type").value("REFUND"))
                .andExpect(jsonPath("$.data.content[0].entries.length()").value(2));
    }

    @Test void redeliveryAndDuplicatePostingsNeverPostTwice() throws Exception {
        UUID payment = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = event("payment.success", eventId, payment, merchant, 1000, "INR");
        deliver(json).andExpect(jsonPath("$.data.outcome").value("POSTED"));
        deliver(json).andExpect(jsonPath("$.data.outcome").value("REPLAYED"));
        // a different event id about the same payment (e.g. a second publisher) must not double count either
        deliver(event("payment.success", UUID.randomUUID(), payment, merchant, 1000, "INR")).andExpect(jsonPath("$.data.outcome").value("REPLAYED"));
        assertEquals(1, jdbc.queryForObject("select count(*) from ledger_transactions where reference_id = ?", Integer.class, payment));
    }

    @Test void parallelDeliveriesOfOneEventPostExactlyOnce() throws Exception {
        UUID payment = UUID.randomUUID();
        String json = event("payment.success", UUID.randomUUID(), payment, merchant, 7000, "INR");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> deliver(json).andReturn().getResponse().getStatus()));
        for (Future<Integer> f : fs) assertEquals(200, f.get());
        pool.shutdown();
        assertEquals(1, jdbc.queryForObject("select count(*) from ledger_transactions where reference_id = ?", Integer.class, payment));
        assertEquals(3, jdbc.queryForObject("select count(*) from ledger_entries e join ledger_transactions t on t.id = e.transaction_id where t.reference_id = ?", Integer.class, payment));
    }

    @Test void eventsWithoutMoneyAreIgnoredAndBadEventsRejected() throws Exception {
        deliver(event("payment.created", UUID.randomUUID(), UUID.randomUUID(), merchant, 100, "INR")).andExpect(jsonPath("$.data.outcome").value("IGNORED"));
        deliver(event("payment.success", UUID.randomUUID(), UUID.randomUUID(), merchant, "10.5", "INR")).andExpect(status().isBadRequest());
        deliver(event("payment.success", UUID.randomUUID(), UUID.randomUUID(), merchant, 10.5, "INR")).andExpect(status().isBadRequest());
        deliver(event("payment.success", UUID.randomUUID(), UUID.randomUUID(), merchant, 0, "INR")).andExpect(status().isBadRequest());
        deliver(event("payment.success", UUID.randomUUID(), UUID.randomUUID(), merchant, -5, "INR")).andExpect(status().isBadRequest());
        deliver(event("payment.success", UUID.randomUUID(), UUID.randomUUID(), merchant, 100, "inr")).andExpect(status().isBadRequest());
        deliver("{\"eventType\":\"payment.success\",\"payload\":{\"amount\":100,\"currency\":\"INR\"}}").andExpect(status().isBadRequest());
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ integrity (enforced by the database)

    @Test void postedRowsCannotBeChangedOrDeleted() throws Exception {
        UUID txId = capture(merchant, 500);
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_entries set amount = 1 where transaction_id = ?", txId));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_entries where transaction_id = ?", txId));
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_transactions set description = 'x' where id = ?", txId));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_transactions where id = ?", txId));
        assertThrows(DataAccessException.class, () -> jdbc.execute("truncate ledger_entries cascade"));
    }

    @Test void databaseRefusesUnbalancedOrEmptyTransactions() {
        UUID acct = jdbc.queryForObject("insert into ledger_accounts (id, code, owner_type, currency, normal_side, created_at) " +
                "values (gen_random_uuid(), 'TEST_ACCT_' || floor(random()*1e9), 'PLATFORM', 'INR', 'DEBIT', now()) returning id", UUID.class);
        assertThrows(NestedRuntimeException.class, () -> tx.executeWithoutResult(s -> {
            UUID t = UUID.randomUUID();
            jdbc.update("insert into ledger_transactions (id, tx_type, reference_id, currency, occurred_at, created_at) values (?, 'TEST', ?, 'INR', now(), now())", t, UUID.randomUUID());
            jdbc.update("insert into ledger_entries (transaction_id, account_id, side, amount, currency, created_at) values (?, ?, 'DEBIT', 100, 'INR', now())", t, acct);
            jdbc.update("insert into ledger_entries (transaction_id, account_id, side, amount, currency, created_at) values (?, ?, 'CREDIT', 99, 'INR', now())", t, acct);
        }), "debit 100 vs credit 99 must not commit");
        assertThrows(NestedRuntimeException.class, () -> tx.executeWithoutResult(s ->
                jdbc.update("insert into ledger_transactions (id, tx_type, reference_id, currency, occurred_at, created_at) values (?, 'TEST', ?, 'INR', now(), now())",
                        UUID.randomUUID(), UUID.randomUUID())), "a transaction without entries must not commit");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "insert into ledger_entries (transaction_id, account_id, side, amount, currency, created_at) values (?, ?, 'DEBIT', 0, 'INR', now())", UUID.randomUUID(), acct));
    }

    // ------------------------------------------------------------------ corrections

    @Test void reversalIsACompensatingTransactionExactlyOnce() throws Exception {
        UUID m = UUID.randomUUID();
        UUID original = capture(m, 4000);
        String res = mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/transactions/" + original + "/reverse").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"posted by mistake\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID reversal = UUID.fromString(mapper.readTree(res).path("data").path("transactionId").asText());
        assertNotEquals(original, reversal);
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/balance"), m, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].payable").value(0)).andExpect(jsonPath("$.data[0].adjustments").value(-(4000 - 103)));
        // the original is still there, untouched
        assertEquals(3, jdbc.queryForObject("select count(*) from ledger_entries where transaction_id = ?", Integer.class, original));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/transactions/" + original + "/reverse").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_REVERSED"));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/transactions/" + reversal + "/reverse").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"undo undo\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_A_REVERSAL"));
        mvc.perform(MockMvcRequestBuilders.post("/internal/v1/ledger/transactions/" + UUID.randomUUID() + "/reverse").header("X-Internal-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")).andExpect(status().isNotFound());
    }

    @Test void providerSettlementMovesReceivableToBankAndFees() throws Exception {
        UUID settlement = UUID.randomUUID();
        String ev = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"settlement.created\",\"aggregateId\":\"" + settlement
                + "\",\"merchantId\":\"00000000-0000-0000-0000-000000000000\",\"occurredAt\":\"2026-10-09T10:00:00Z\",\"payload\":{\"provider\":\"payu\","
                + "\"currency\":\"INR\",\"gross\":22000,\"refunds\":2000,\"providerFees\":519,\"amount\":19481}}";
        deliver(ev).andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("POSTED"));
        deliver(ev).andExpect(jsonPath("$.data.outcome").value("REPLAYED"));
        List<String> rows = jdbc.queryForList("""
                SELECT a.code || ':' || e.side || ':' || e.amount FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.account_id
                JOIN ledger_transactions t ON t.id = e.transaction_id WHERE t.reference_id = ? ORDER BY e.id""", String.class, settlement);
        assertEquals(List.of("APG_BANK:DEBIT:19481", "PROVIDER_FEE_EXPENSE:DEBIT:519", "PROVIDER_RECEIVABLE_PAYU:CREDIT:20000"), rows);
        assertEquals("DEBIT", jdbc.queryForObject("select normal_side from ledger_accounts where code = 'PROVIDER_FEE_EXPENSE'", String.class));
        deliver(ev.replace("19481", "-1").replace(UUID.randomUUID().toString(), "x")).andExpect(status().is4xxClientError());
    }

    @Test void theBooksBalanceAfterEverythingAbove() throws Exception {
        capture(UUID.randomUUID(), 12345);
        JsonNode t = mapper.readTree(mvc.perform(MockMvcRequestBuilders.get("/internal/v1/ledger/trial-balance").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        assertTrue(t.path("balanced").asBoolean(), t.toString());
        assertEquals(t.path("totalsByCurrency").path("INR").get(0).asLong(), t.path("totalsByCurrency").path("INR").get(1).asLong());
    }

    // ------------------------------------------------------------------ access

    @Test void merchantsSeeOnlyTheirOwnLedgerAndApiKeysAreNotAccepted() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        capture(a, 900);
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/balance"), b, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/transactions"), b, "MERCHANT_ADMIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/ledger/balance").header("X-Internal-Token", TOKEN)
                .header("X-Auth-Type", "API_KEY").header("X-Merchant-Id", a.toString())).andExpect(status().isForbidden());
        mvc.perform(asUser(MockMvcRequestBuilders.get("/api/v1/ledger/balance"), a, "APG_FINANCE")).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/v1/ledger/balance")).andExpect(status().isUnauthorized());
    }

    @Test void feeNeverExceedsTheAmount() {
        LedgerProperties.Fee fee = new LedgerProperties.Fee(250, 3);
        assertEquals(253, fee.on(10000));
        assertEquals(2, fee.on(2), "fixed fee larger than the payment is capped at the payment");
        assertEquals(0, new LedgerProperties.Fee(0, 0).on(1000));
        assertThrows(IllegalStateException.class, () -> new LedgerProperties.Fee(10001, 0));
    }
}
