package dev.supavolt.api.features.storage;

import dev.supavolt.api.common.Tokens;
import dev.supavolt.api.config.SupavoltProperties;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 or any S3-compatible endpoint (Cloudflare R2, MinIO). Bytes never pass through the API: the
 * browser PUTs to a presigned URL and downloads from a public or presigned one. Presigned URLs
 * use the endpoint's own scheme, so plain-http MinIO works locally.
 */
@Component
public class S3ObjectStore implements ObjectStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final SupavoltProperties.Storage options;
    private final Clock clock;
    private final S3Client s3;
    private final S3Presigner presigner;

    public S3ObjectStore(SupavoltProperties properties, Clock clock) {
        this.options = properties.storage();
        this.clock = clock;

        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(options.accessKeyId(), options.secretAccessKey()));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        // With a custom endpoint the region only feeds the signature. R2 wants "auto", which AWS
        // regions reject, and MinIO accepts any; us-east-1 is what both treat as the default.
        var region = Region.of(options.serviceUrl().isBlank() || !"auto".equals(options.region())
                ? options.region()
                : "us-east-1");

        var client = S3Client.builder().region(region).credentialsProvider(credentials).serviceConfiguration(pathStyle);
        var signer = S3Presigner.builder().region(region).credentialsProvider(credentials).serviceConfiguration(pathStyle);

        if (!options.serviceUrl().isBlank()) {
            client.endpointOverride(URI.create(options.serviceUrl()));
            signer.endpointOverride(URI.create(options.serviceUrl()));
        }

        this.s3 = client.build();
        this.presigner = signer.build();
    }

    /** Local development convenience (supavolt.storage.create-bucket); production buckets are provisioned. */
    @EventListener(ApplicationReadyEvent.class)
    public void createBucketIfMissing() {
        if (!options.createBucket()) return;

        try {
            s3.headBucket(r -> r.bucket(options.bucket()));
        } catch (NoSuchBucketException e) {
            s3.createBucket(r -> r.bucket(options.bucket()));
            log.info("Created storage bucket {}", options.bucket());
        } catch (SdkException e) {
            log.warn("Storage endpoint unreachable; bucket {} not checked: {}", options.bucket(), e.getMessage());
        }
    }

    @Override
    public String buildKey(UUID projectId, UUID bucketId, String fileName) {
        // The key is built server-side so a caller cannot write outside its own prefix.
        var safeName = fileName.substring(fileName.lastIndexOf('/') + 1).replace('\\', '_');
        return projectId + "/" + bucketId + "/" + Tokens.uuidV7() + "/" + safeName;
    }

    @Override
    public Presigned presignPut(String key, String contentType) {
        var lifetime = options.uploadUrlLifetime();
        var request = presigner.presignPutObject(p -> p
                .signatureDuration(lifetime)
                .putObjectRequest(o -> o.bucket(options.bucket()).key(key).contentType(contentType)));

        return new Presigned(request.url().toString(), OffsetDateTime.now(clock).plus(lifetime));
    }

    @Override
    public Presigned presignGet(String key) {
        var lifetime = options.downloadUrlLifetime();
        var request = presigner.presignGetObject(p -> p
                .signatureDuration(lifetime)
                .getObjectRequest(o -> o.bucket(options.bucket()).key(key)));

        return new Presigned(request.url().toString(), OffsetDateTime.now(clock).plus(lifetime));
    }

    @Override
    public String publicUrl(String key) {
        var base = options.publicBaseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/" + key;
    }

    @Override
    public void delete(List<String> keys) {
        // S3 batch delete caps at 1000 keys per request.
        for (var from = 0; from < keys.size(); from += 1000) {
            var batch = keys.subList(from, Math.min(from + 1000, keys.size())).stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();

            s3.deleteObjects(r -> r.bucket(options.bucket()).delete(Delete.builder().objects(batch).build()));
        }
    }

    @Override
    public void destroy() {
        presigner.close();
        s3.close();
    }
}
