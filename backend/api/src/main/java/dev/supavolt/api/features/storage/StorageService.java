package dev.supavolt.api.features.storage;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.infrastructure.persistence.StorageBucket;
import dev.supavolt.api.infrastructure.persistence.StorageBucketRepository;
import dev.supavolt.api.infrastructure.persistence.StorageObject;
import dev.supavolt.api.infrastructure.persistence.StorageObjectRepository;
import dev.supavolt.contracts.Contracts.BucketAccess;
import dev.supavolt.contracts.Contracts.CreateBucketRequest;
import dev.supavolt.contracts.Contracts.RegisterObjectRequest;
import dev.supavolt.contracts.Contracts.SignedUrlResponse;
import dev.supavolt.contracts.Contracts.StorageBucketDto;
import dev.supavolt.contracts.Contracts.StorageObjectDto;
import dev.supavolt.contracts.Contracts.UploadUrlRequest;
import dev.supavolt.contracts.Contracts.UploadUrlResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Every lookup is scoped by project id. The original took a bucket or object id straight from
 * the route with no ownership check, so any member of any org could delete any object.
 */
@Service
public class StorageService {

    private final StorageBucketRepository buckets;
    private final StorageObjectRepository objects;
    private final ObjectStore store;
    private final long maxUploadBytes;

    public StorageService(
            StorageBucketRepository buckets, StorageObjectRepository objects, ObjectStore store, SupavoltProperties properties) {
        this.buckets = buckets;
        this.objects = objects;
        this.store = store;
        this.maxUploadBytes = properties.storage().maxUploadBytes();
    }

    public List<StorageBucketDto> listBuckets(UUID projectId) {
        return buckets.findByProjectIdOrderByName(projectId).stream().map(StorageService::toDto).toList();
    }

    public StorageBucketDto createBucket(UUID projectId, CreateBucketRequest req) {
        var name = req.name().trim();
        if (name.isEmpty()) throw AppException.badRequest("Bucket name is required");

        var bucket = new StorageBucket();
        bucket.setProjectId(projectId);
        bucket.setName(name);
        bucket.setAccess(req.access());
        buckets.save(bucket);

        return toDto(bucket);
    }

    private StorageBucket bucket(UUID projectId, UUID bucketId) {
        return buckets.findByIdAndProjectId(bucketId, projectId).orElseThrow(() -> AppException.notFound("Bucket"));
    }

    private StorageObject object(UUID projectId, UUID objectId) {
        return objects.findInProject(objectId, projectId).orElseThrow(() -> AppException.notFound("File"));
    }

    public UUID bucketIdByName(UUID projectId, String name) {
        return buckets.findByNameAndProjectId(name, projectId).orElseThrow(() -> AppException.notFound("Bucket")).getId();
    }

    public void deleteBucket(UUID projectId, UUID bucketId) {
        var bucket = bucket(projectId, bucketId);

        var keys = objects.findKeysInBucket(bucket.getId());
        if (!keys.isEmpty()) store.delete(keys);

        // Object rows go with the bucket through the foreign key's ON DELETE CASCADE.
        buckets.delete(bucket);
    }

    public List<StorageObjectDto> listObjects(UUID projectId, UUID bucketId) {
        return objects.findInBucket(bucketId, projectId).stream().map(StorageService::toDto).toList();
    }

    public UploadUrlResponse createUploadUrl(UUID projectId, UUID bucketId, UploadUrlRequest req) {
        if (req.size() > maxUploadBytes)
            throw AppException.badRequest("File exceeds the " + maxUploadBytes / 1024 / 1024 + " MB limit");

        var bucket = bucket(projectId, bucketId);
        var key = store.buildKey(projectId, bucket.getId(), req.fileName());
        var presigned = store.presignPut(key, req.contentType());

        return new UploadUrlResponse(presigned.url(), key, presigned.expiresAt());
    }

    public StorageObjectDto registerObject(UUID projectId, UUID bucketId, RegisterObjectRequest req) {
        var bucket = bucket(projectId, bucketId);

        // The key was minted by createUploadUrl for this project and bucket. Re-check the prefix
        // so a caller cannot register a key belonging to another project.
        if (!req.objectKey().startsWith(projectId + "/" + bucket.getId() + "/"))
            throw AppException.forbidden("Object key does not belong to this bucket");

        var entity = new StorageObject();
        entity.setBucketId(bucket.getId());
        entity.setName(req.name());
        entity.setSize(req.size());
        entity.setMimeType(req.contentType());
        entity.setObjectKey(req.objectKey());
        entity.setUrl(bucket.getAccess() == BucketAccess.PUBLIC ? store.publicUrl(req.objectKey()) : "");
        objects.save(entity);

        return toDto(entity);
    }

    public void deleteObject(UUID projectId, UUID objectId) {
        var obj = object(projectId, objectId);

        store.delete(List.of(obj.getObjectKey()));
        objects.delete(obj);
    }

    public SignedUrlResponse signedUrl(UUID projectId, UUID objectId) {
        var presigned = store.presignGet(object(projectId, objectId).getObjectKey());
        return new SignedUrlResponse(presigned.url(), presigned.expiresAt());
    }

    private static StorageBucketDto toDto(StorageBucket b) {
        return new StorageBucketDto(b.getId(), b.getProjectId(), b.getName(), b.getAccess(), b.getCreatedAt());
    }

    private static StorageObjectDto toDto(StorageObject o) {
        return new StorageObjectDto(o.getId(), o.getBucketId(), o.getName(), o.getSize(), o.getMimeType(),
                o.getObjectKey(), o.getUrl(), o.getCreatedAt());
    }
}
