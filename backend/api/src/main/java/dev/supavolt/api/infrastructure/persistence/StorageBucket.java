package dev.supavolt.api.infrastructure.persistence;

import dev.supavolt.contracts.Contracts.BucketAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A named bucket inside a project.
 */
@Entity
@Table(name = "storage_buckets")
public class StorageBucket extends BaseEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "name")
    private String name;

    @Column(name = "access")
    @Convert(converter = Converters.BucketAccessText.class)
    private BucketAccess access = BucketAccess.PUBLIC;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void stampCreated() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BucketAccess getAccess() {
        return access;
    }

    public void setAccess(BucketAccess access) {
        this.access = access;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
