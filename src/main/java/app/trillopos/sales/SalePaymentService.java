package app.trillopos.sales;

import java.math.BigDecimal;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import app.trillopos.shared.tenant.TenantContext;

/** One ledger projection shared by receipts, the sales log and payment filters. */
@Service
public class SalePaymentService {
    public record SalePaymentState(SalePaymentStatus status, UUID receivableId,
            BigDecimal receivedAmount, BigDecimal refundedAmount, BigDecimal netReceivedAmount,
            BigDecimal creditReleasedAmount, BigDecimal writtenOffAmount, BigDecimal outstandingAmount) {}

    private record Row(UUID id, SalePaymentState state) {}

    // Explicit tenant predicates also protect native reads when used with an owner connection.
    private static final String PROJECTION = """
        WITH money AS (
          SELECT s.id, s.status, s.total, s.created_at, r.id AS receivable_id,
            coalesce(r.written_off_amount, 0) AS written_off, r.outstanding_amount AS receivable_outstanding,
            coalesce((SELECT sum(p.amount) FROM payment p WHERE p.sale_id=s.id
              AND p.organization_id=s.organization_id AND p.method <> 'CREDIT'),0)
              + coalesce((SELECT sum(t.amount) FROM receivable_settlement t WHERE t.receivable_id=r.id
                AND t.organization_id=s.organization_id AND t.method <> 'CREDIT'),0) AS received,
            coalesce((SELECT sum(x.refund_amount-x.credit_refund_amount) FROM sale_return x
              WHERE x.original_sale_id=s.id AND x.organization_id=s.organization_id),0) AS refunded,
            coalesce((SELECT sum(x.credit_refund_amount) FROM sale_return x
              WHERE x.original_sale_id=s.id AND x.organization_id=s.organization_id),0) AS released,
            coalesce((SELECT sum(x.refund_amount) FROM sale_return x
              WHERE x.original_sale_id=s.id AND x.organization_id=s.organization_id),0) AS returned
          FROM sale s LEFT JOIN receivable r ON r.source_id=s.id AND r.source_type='SALE'
            AND r.organization_id=s.organization_id
          WHERE s.organization_id=:org %s
        ), amounts AS (
          SELECT *, received-refunded AS net_received, greatest(total-returned,0) AS remaining
          FROM money
        ), states AS (
          SELECT *, coalesce(receivable_outstanding, greatest(remaining-net_received-written_off,0)) AS outstanding,
            CASE
              WHEN status='REFUNDED' AND refunded>0 AND net_received<=0 THEN 'REFUNDED'
              WHEN (remaining>0 OR total=0) AND net_received>=remaining THEN 'PAID'
              WHEN net_received>0 THEN 'DEPOSIT'
              ELSE 'UNPAID'
            END AS payment_status
          FROM amounts
        )
        SELECT * FROM states %s
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;

    SalePaymentService(NamedParameterJdbcTemplate jdbc, EntityManager entityManager) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public Map<UUID, SalePaymentState> forSales(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        flushWrites();
        return jdbc.query(PROJECTION.formatted("AND s.id IN (:ids)", ""),
                Map.of("org", TenantContext.requireOrganizationId(), "ids", ids), (rs, n) ->
                    new Row(rs.getObject("id", UUID.class), new SalePaymentState(
                        SalePaymentStatus.valueOf(rs.getString("payment_status")),
                        rs.getObject("receivable_id", UUID.class), rs.getBigDecimal("received"),
                        rs.getBigDecimal("refunded"), rs.getBigDecimal("net_received"),
                        rs.getBigDecimal("released"), rs.getBigDecimal("written_off"),
                        rs.getBigDecimal("outstanding"))))
                .stream().collect(Collectors.toMap(Row::id, Row::state));
    }

    public SalePaymentState forSale(UUID id) {
        return forSales(List.of(id)).get(id);
    }

    /** Filter before the limit is applied, so older matching sales cannot disappear. */
    @Transactional(readOnly = true)
    public List<UUID> matching(SaleService.SaleQuery query, UUID locationId) {
        flushWrites();
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("org", TenantContext.requireOrganizationId());
        parameters.put("paymentStatus", query.paymentStatus().name());
        parameters.put("limit", Math.clamp(query.limit(), 1, 500));
        StringBuilder candidates = new StringBuilder("AND s.status IN ('COMPLETED','PARTIALLY_REFUNDED','REFUNDED')");
        if (locationId != null) { candidates.append(" AND s.location_id=:location"); parameters.put("location", locationId); }
        if (query.customerId() != null) { candidates.append(" AND s.customer_id=:customer"); parameters.put("customer", query.customerId()); }
        if (query.progress() != null) { candidates.append(" AND s.progress=:progress"); parameters.put("progress", query.progress().name()); }
        if (query.statuses() != null && !query.statuses().isEmpty()) {
            candidates.append(" AND s.status IN (:statuses)");
            parameters.put("statuses", query.statuses().stream().map(Enum::name).toList());
        }
        if (query.from() != null) { candidates.append(" AND s.sold_at>=:from"); parameters.put("from", java.sql.Timestamp.from(query.from())); }
        if (query.to() != null) { candidates.append(" AND s.sold_at<:to"); parameters.put("to", java.sql.Timestamp.from(query.to())); }
        // IDs are bounded only after all filters; avoid an unbounded IN list for a large shop.
        return jdbc.query(PROJECTION.formatted(candidates,
                "WHERE payment_status=:paymentStatus ORDER BY created_at DESC LIMIT :limit"), parameters,
                (rs, n) -> rs.getObject("id", UUID.class));
    }

    private void flushWrites() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) entityManager.flush();
    }
}
