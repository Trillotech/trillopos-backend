package app.trillopos.sales;

import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import app.trillopos.shared.persistence.TenantEntity;

/** Append-only work history; inherited createdAt/createdBy record time and actor. */
@Entity
@Immutable
@Table(name = "sale_progress_event")
public class SaleProgressEvent extends TenantEntity {
    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_progress", nullable = false, length = 32, updatable = false)
    private SaleProgress fromProgress;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_progress", nullable = false, length = 32, updatable = false)
    private SaleProgress toProgress;
    @Column(name = "reason", nullable = false, length = 500, updatable = false)
    private String reason;
    protected SaleProgressEvent() {}
    SaleProgressEvent(UUID saleId, SaleProgress from, SaleProgress to, String reason) {
        this.saleId = saleId; this.fromProgress = from; this.toProgress = to; this.reason = reason;
    }
    public UUID getSaleId() { return saleId; }
    public SaleProgress getFromProgress() { return fromProgress; }
    public SaleProgress getToProgress() { return toProgress; }
    public String getReason() { return reason; }
}
