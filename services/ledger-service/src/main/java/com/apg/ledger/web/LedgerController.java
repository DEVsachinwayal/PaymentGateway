package com.apg.ledger.web;

import com.apg.common.api.ApiResponse;
import com.apg.common.web.CallerContext;
import com.apg.ledger.service.LedgerQueries;
import com.apg.ledger.service.LedgerQueries.Balance;
import com.apg.ledger.service.LedgerQueries.PageView;
import com.apg.ledger.service.LedgerQueries.TransactionView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Merchant-facing, read-only. Dashboard users only: API keys are not accepted by the gateway for this path. */
@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {
    private final LedgerQueries queries;

    public LedgerController(LedgerQueries queries) {
        this.queries = queries;
    }

    private static UUID merchantOf(CallerContext c) {
        c.requireAnyRole("MERCHANT_ADMIN", "MERCHANT_DEVELOPER");
        return c.requireMerchant();
    }

    @GetMapping("/balance")
    ApiResponse<List<Balance>> balance(CallerContext c) {
        return ApiResponse.ok(queries.balances(merchantOf(c)));
    }

    @GetMapping("/transactions")
    ApiResponse<PageView<TransactionView>> transactions(CallerContext c, @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(queries.transactions(merchantOf(c), page, size));
    }
}
