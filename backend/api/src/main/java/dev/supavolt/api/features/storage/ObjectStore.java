package dev.supavolt.api.features.storage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ObjectStore {

    record Presigned(String url, OffsetDateTime expiresAt) {
    }

    String buildKey(UUID projectId, UUID bucketId, String fileName);

    Presigned presignPut(String key, String contentType);

    Presigned presignGet(String key);

    String publicUrl(String key);

    void delete(List<String> keys);
}
