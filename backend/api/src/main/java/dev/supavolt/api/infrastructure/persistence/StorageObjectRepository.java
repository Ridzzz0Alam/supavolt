package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageObjectRepository extends JpaRepository<StorageObject, UUID> {

    @Query("select o from StorageObject o join StorageBucket b on b.id = o.bucketId where o.id = :id and b.projectId = :projectId")
    Optional<StorageObject> findInProject(UUID id, UUID projectId);

    @Query("select o from StorageObject o join StorageBucket b on b.id = o.bucketId"
            + " where o.bucketId = :bucketId and b.projectId = :projectId order by o.createdAt desc")
    List<StorageObject> findInBucket(UUID bucketId, UUID projectId);

    @Query("select o.objectKey from StorageObject o where o.bucketId = :bucketId")
    List<String> findKeysInBucket(UUID bucketId);
}
