package app.trillopos.sales;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import app.trillopos.finance.Receivable;
import app.trillopos.finance.ReceivableRepository;
import app.trillopos.finance.ReceivableService;
import app.trillopos.finance.ReceivableService.SettleCommand;
import app.trillopos.shared.tenant.TenantContext;
import app.trillopos.shared.web.ApiException;

/** Work progress does not post stock or money. Cancellation does, atomically, through returns. */
@Service
public class SaleWorkflowService {
    public record CancelCommand(String reason, Boolean restock, PaymentMethod refundMethod,
            UUID cashierShiftId, String referenceNo, String idempotencyKey,
            BigDecimal expectedNetReceivedAmount, BigDecimal expectedOutstandingAmount) {}
    public record Cancellation(UUID saleId, UUID returnId, boolean replayed) {}

    private final SaleRepository sales;
    private final SaleLineRepository lines;
    private final SaleReturnLineRepository returned;
    private final SaleProgressEventRepository events;
    private final SalePaymentService paymentStates;
    private final ReceivableService receivables;
    private final ReceivableRepository receivableRows;
    private final ReturnService returns;

    SaleWorkflowService(SaleRepository sales, SaleLineRepository lines, SaleReturnLineRepository returned,
            SaleProgressEventRepository events, SalePaymentService paymentStates,
            ReceivableService receivables, ReceivableRepository receivableRows, ReturnService returns) {
        this.sales=sales; this.lines=lines; this.returned=returned; this.events=events;
        this.paymentStates=paymentStates; this.receivables=receivables;
        this.receivableRows=receivableRows; this.returns=returns;
    }

    @Transactional
    public void progress(UUID id, SaleProgress next, String reason) {
        Sale sale = lock(id);
        requireReason(reason);
        if (next == null || next == SaleProgress.CANCELED)
            throw ApiException.badRequest("use_sale_cancellation", "cancel through the cancellation action");
        if (sale.getProgress() == SaleProgress.CANCELED)
            throw ApiException.conflict("sale_canceled", "a canceled sale cannot be reopened");
        if (sale.getStatus() == SaleStatus.REFUNDED && next == SaleProgress.OPEN)
            throw ApiException.conflict("sale_fully_refunded", "a fully returned sale cannot be reopened");
        change(sale, next, reason.trim());
    }

    @Transactional
    public Cancellation cancel(UUID id, CancelCommand command) {
        Sale sale = lock(id);
        requireReason(command.reason());
        if (command.restock() == null)
            throw ApiException.badRequest("restock_required", "explicitly choose whether goods return to stock");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank() || command.idempotencyKey().length() > 100)
            throw ApiException.badRequest("invalid_idempotency_key", "the key is 1-100 characters");
        if (sale.getProgress() == SaleProgress.CANCELED) return new Cancellation(id, null, true);
        receivableRows.lockForSale(id);
        var state = paymentStates.forSale(id);
        if ((command.expectedNetReceivedAmount() != null
                && command.expectedNetReceivedAmount().compareTo(state.netReceivedAmount()) != 0)
                || (command.expectedOutstandingAmount() != null
                && command.expectedOutstandingAmount().compareTo(state.outstandingAmount()) != 0))
            throw ApiException.conflict("cancellation_changed", "payments or returns changed; review the cancellation amounts again");
        if (state.writtenOffAmount().signum() > 0)
            throw ApiException.conflict("sale_credit_written_off", "this sale's debt was written off; it needs an accounting review before cancellation");
        UUID returnId = null;
        if (sale.getStatus() != SaleStatus.REFUNDED) {
            List<SaleLine> sold = lines.findAllBySaleIdOrderByPosition(id);
            Map<UUID, BigDecimal> quantities = returned.returnedSoFar(sold.stream().map(SaleLine::getId).toList())
                    .stream().collect(Collectors.toMap(Returned::saleLineId, Returned::quantity));
            List<ReturnService.LineCommand> remaining = sold.stream().map(l -> new ReturnService.LineCommand(l.getId(),
                    l.getQuantity().subtract(quantities.getOrDefault(l.getId(), BigDecimal.ZERO)), command.restock()))
                    .filter(l -> l.quantity().signum() > 0).toList();
            PaymentMethod method = state.netReceivedAmount().signum() > 0 ? command.refundMethod() : PaymentMethod.CREDIT;
            if (state.netReceivedAmount().signum() > 0 && (method == null || method == PaymentMethod.CREDIT))
                throw ApiException.badRequest("refund_method_required", "choose how the collected money goes back");
            // A zero-price walk-in return needs no credit account and moves no money.
            if (method == PaymentMethod.CREDIT && sale.getCustomerId() == null) method = PaymentMethod.CASH;
            var result = returns.create(new ReturnService.ReturnCommand(id, sale.getLocationId(),
                    method == PaymentMethod.CASH ? command.cashierShiftId() : null, method,
                    command.referenceNo(), command.reason().trim(), remaining, cancellationKey(command.idempotencyKey()),
                    state.outstandingAmount()));
            returnId = result.details().saleReturn().getId();
            if (sale.getStatus() != SaleStatus.REFUNDED)
                throw ApiException.conflict("idempotency_key_used", "this cancellation key belongs to another return");
        } else if (state.outstandingAmount().signum() > 0 || state.netReceivedAmount().signum() > 0) {
            throw ApiException.conflict("sale_refund_incomplete", "this returned sale still has money or credit to reconcile");
        }
        change(sale, SaleProgress.CANCELED, command.reason().trim());
        return new Cancellation(id, returnId, false);
    }

    @Transactional
    public ReceivableService.SettlementResult payment(UUID id, SettleCommand command) {
        Sale sale = lock(id);
        if (sale.getProgress() == SaleProgress.CANCELED)
            throw ApiException.conflict("sale_canceled", "a canceled sale cannot collect payment");
        Receivable receivable = receivableRows.lockForSale(id).orElse(null);
        if (receivable == null) throw ApiException.conflict("no_receivable", "this sale has no unpaid credit balance");
        TenantContext.requireLocationInScope(command.locationId());
        if (!sale.getLocationId().equals(command.locationId()))
            throw ApiException.badRequest("payment_location_mismatch", "record the payment at this sale's location");
        if (command.cashierShiftId() != null && command.method() != PaymentMethod.CASH)
            throw ApiException.badRequest("unexpected_shift", "only a cash payment enters a drawer");
        return receivables.settle(receivable.getId(), command);
    }

    @Transactional(readOnly = true)
    public List<SaleProgressEvent> history(UUID id) {
        Sale sale = sales.findById(id).orElseThrow(() -> ApiException.notFound("sale_not_found", "no such sale"));
        TenantContext.requireLocationInScope(sale.getLocationId());
        return events.findAllBySaleIdOrderByCreatedAtAscIdAsc(id);
    }

    private Sale lock(UUID id) {
        Sale sale = sales.lockById(id).orElseThrow(() -> ApiException.notFound("sale_not_found", "no such sale"));
        TenantContext.requireLocationInScope(sale.getLocationId());
        if (!sale.tookMoney()) throw ApiException.conflict("sale_not_completed", "only a posted sale has work progress");
        return sale;
    }

    private void change(Sale sale, SaleProgress next, String reason) {
        if (sale.getProgress() == next) return;
        events.save(new SaleProgressEvent(sale.getId(), sale.getProgress(), next, reason));
        sale.setProgress(next);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw ApiException.badRequest("reason_required", "give a reason of 1-500 characters");
    }

    private static String cancellationKey(String key) {
        try { return "cancel:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
