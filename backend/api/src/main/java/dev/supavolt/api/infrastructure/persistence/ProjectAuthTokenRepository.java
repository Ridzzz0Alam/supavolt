package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ProjectAuthTokenRepository extends JpaRepository<ProjectAuthToken, UUID> {

    Optional<ProjectAuthToken> findByTokenHashAndPurposeAndProjectId(String tokenHash, String purpose, UUID projectId);
}
