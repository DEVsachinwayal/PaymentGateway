package com.apg.ledger.web;

import com.apg.common.api.ApiResponse;
import com.apg.ledger.service.LedgerEvents;
import com.apg.ledger.service.LedgerEvents.Handled;
import com.apg.ledger.service.LedgerQueries;
import com.apg.ledger.service.LedgerQueries.TrialBalance;
import com.apg.ledger.service.LedgerService;
import com.apg.ledger.service.LedgerService.Result;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Internal (token protected, not routed by the gateway): event delivery, compensating reversals, trial balance. */
@RestController
@RequestMapping("/internal/v1/ledger")
public class InternalLedgerController {
    public record ReverseRequest(@NotBlank @Size(max = 200) String reason) {}

    private final LedgerEvents events;
    private final LedgerService ledger;
    private final LedgerQueries queries;
    private final com.apg.common.audit.AuditLog audit;

    public InternalLedgerController(LedgerEvents events, LedgerService ledger, LedgerQueries queries, com.apg.common.audit.AuditLog audit) {
        this.audit = audit;
        this.events = events;
        this.ledger = ledger;
        this.queries = queries;
    }

    /** Idempotent: delivering the same event twice posts once. Same handler the Kafka consumer will call (M12). */
    @PostMapping("/events")
    ApiResponse<Handled> event(@RequestBody JsonNode event) {
        return ApiResponse.ok(events.handle(event));
    }

    @PostMapping("/transactions/{id}/reverse")
    ApiResponse<Result> reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest r,
                                @RequestHeader(value = "X-Operator", required = false) String operator) {
        String actor = "ops:" + (operator == null || operator.isBlank() ? "unspecified" : operator);
        audit.record(actor, null, "LEDGER_REVERSE", id.toString(), "ATTEMPT", java.util.Map.of("reason", r.reason()));
        Result res = ledger.reverse(id, r.reason());
        audit.record(actor, null, "LEDGER_REVERSE", id.toString(), "SUCCESS", java.util.Map.of("reason", r.reason(), "reversalId", res.transactionId().toString()));
        return ApiResponse.ok(res);
    }

    @GetMapping("/trial-balance")
    ApiResponse<TrialBalance> trialBalance() {
        return ApiResponse.ok(queries.trialBalance());
    }
}
