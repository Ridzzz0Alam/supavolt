package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageBucketRepository extends JpaRepository<StorageBucket, UUID> {

    List<StorageBucket> findByProjectIdOrderByName(UUID projectId);

    Optional<StorageBucket> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<StorageBucket> findByNameAndProjectId(String name, UUID projectId);
}
