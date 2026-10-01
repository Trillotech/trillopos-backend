package app.trillopos.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import app.trillopos.finance.ReceivableService;

@RestController
@RequestMapping("/sales/{id}")
@PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER', 'CASHIER')")
class SaleWorkflowController {
    record SaleProgressRequest(@NotNull SaleProgress progress, @NotBlank @Size(max=500) String reason) {}
    record SaleCancelRequest(@NotBlank @Size(max=500) String reason, @NotNull Boolean restock,
            PaymentMethod refundMethod, UUID cashierShiftId, @Size(max=100) String referenceNo,
            @NotBlank @Size(max=100) String idempotencyKey,
            @DecimalMin("0") BigDecimal expectedNetReceivedAmount,
            @DecimalMin("0") BigDecimal expectedOutstandingAmount) {}
    record SaleCollectRequest(@NotNull BigDecimal amount, @NotNull PaymentMethod method,
            @NotNull UUID locationId, UUID cashierShiftId, @Size(max=100) String referenceNo,
            @Size(max=500) String note, @NotBlank @Size(max=100) String idempotencyKey) {}
    record SaleProgressEventView(UUID id, SaleProgress fromProgress, SaleProgress toProgress,
            String reason, Instant changedAt, UUID changedBy) {}
    record SaleActionView(UUID saleId, boolean replayed) {}

    private final SaleWorkflowService workflow;
    SaleWorkflowController(SaleWorkflowService workflow) { this.workflow=workflow; }

    @PostMapping("/progress")
    SaleActionView progress(@PathVariable UUID id, @Valid @RequestBody SaleProgressRequest request) {
        workflow.progress(id, request.progress(), request.reason());
        return new SaleActionView(id, false);
    }

    @GetMapping("/progress")
    List<SaleProgressEventView> history(@PathVariable UUID id) {
        return workflow.history(id).stream().map(e -> new SaleProgressEventView(e.getId(), e.getFromProgress(),
                e.getToProgress(), e.getReason(), e.getCreatedAt(), e.getCreatedBy())).toList();
    }

    @PostMapping("/cancel")
    @PreAuthorize("hasAnyRole('OWNER', 'STOCK_MANAGER')")
    SaleWorkflowService.Cancellation cancel(@PathVariable UUID id, @Valid @RequestBody SaleCancelRequest request) {
        return workflow.cancel(id, new SaleWorkflowService.CancelCommand(request.reason(), request.restock(),
                request.refundMethod(), request.cashierShiftId(), request.referenceNo(), request.idempotencyKey(),
                request.expectedNetReceivedAmount(), request.expectedOutstandingAmount()));
    }

    @PostMapping("/payments")
    SaleActionView payment(@PathVariable UUID id, @Valid @RequestBody SaleCollectRequest request) {
        var result = workflow.payment(id, new ReceivableService.SettleCommand(request.amount(), request.method(),
                request.locationId(), request.cashierShiftId(), request.referenceNo(), null, request.note(), request.idempotencyKey()));
        return new SaleActionView(id, result.replayed());
    }
}
