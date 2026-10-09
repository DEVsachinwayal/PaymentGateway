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

    public InternalLedgerController(LedgerEvents events, LedgerService ledger, LedgerQueries queries) {
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
    ApiResponse<Result> reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest r) {
        return ApiResponse.ok(ledger.reverse(id, r.reason()));
    }

    @GetMapping("/trial-balance")
    ApiResponse<TrialBalance> trialBalance() {
        return ApiResponse.ok(queries.trialBalance());
    }
}
