package com.apg.orchestrator.web;

import com.apg.common.api.ApiResponse;
import com.apg.orchestrator.service.VerificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Called by the webhook service after an (authentic) callback, and usable by ops tooling. Not routed by the gateway. */
@RestController
@RequestMapping("/internal/v1/orchestrator")
public class InternalOrchestratorController {
    public record VerifyRequest(@NotBlank @Pattern(regexp = "^(WEBHOOK|VERIFICATION)$") String trigger) {}

    private final VerificationService verification;

    public InternalOrchestratorController(VerificationService verification) { this.verification = verification; }

    @PostMapping("/attempts/{reference}/verify")
    ApiResponse<Map<String, String>> verify(@PathVariable String reference, @Valid @RequestBody VerifyRequest r) {
        return ApiResponse.ok(Map.of("reference", reference, "result", verification.verify(reference, r.trigger()).name()));
    }
}
