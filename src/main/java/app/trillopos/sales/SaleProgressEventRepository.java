package app.trillopos.sales;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleProgressEventRepository extends JpaRepository<SaleProgressEvent, UUID> {
    List<SaleProgressEvent> findAllBySaleIdOrderByCreatedAtAscIdAsc(UUID saleId);
}
