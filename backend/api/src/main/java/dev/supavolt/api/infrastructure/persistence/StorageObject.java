package dev.supavolt.api.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * A file registered in a bucket. The bytes live in the object store.
 */
@Entity
@Table(name = "storage_objects")
public class StorageObject extends BaseEntity {

    @Column(name = "bucket_id")
    private UUID bucketId;

    @Column(name = "name")
    private String name;

    @Column(name = "size")
    private long size;

    @Column(name = "mime_type")
    private String mimeType;

    /** Key in the object store: {projectId}/{bucketId}/{uuid}/{fileName}. */
    @Column(name = "object_key")
    private String objectKey;

    /** Public URL for public buckets; empty for private ones, which are signed on demand. */
    @Column(name = "url")
    private String url = "";

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void stampCreated() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
    }

    public UUID getBucketId() {
        return bucketId;
    }

    public void setBucketId(UUID bucketId) {
        this.bucketId = bucketId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
