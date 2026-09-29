package app.trillopos.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Tenant-scoped by {@code @TenantId}. The cross-organization reads live in {@code MembershipDirectory}. */
public interface MembershipRepository extends JpaRepository<Membership, UUID> {

    List<Membership> findAllByOrderByCreatedAtAsc();

    boolean existsByAccountId(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Membership m where m.id = :id")
    Optional<Membership> lockById(UUID id);
}
