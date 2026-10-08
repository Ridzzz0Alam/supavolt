package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    Optional<Invite> findByTokenHash(String tokenHash);

    /** Superseding an outstanding invite keeps one live token per email and org. */
    @Transactional
    @Modifying
    @Query("update Invite i set i.revokedAt = :now where i.orgId = :orgId and i.email = :email"
            + " and i.acceptedAt is null and i.revokedAt is null")
    int revokeOutstanding(UUID orgId, String email, OffsetDateTime now);
}
