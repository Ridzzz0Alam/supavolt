package dev.supavolt.api.features.storage;

import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.contracts.Contracts.CreateBucketRequest;
import dev.supavolt.contracts.Contracts.RegisterObjectRequest;
import dev.supavolt.contracts.Contracts.SignedUrlResponse;
import dev.supavolt.contracts.Contracts.StorageBucketDto;
import dev.supavolt.contracts.Contracts.StorageObjectDto;
import dev.supavolt.contracts.Contracts.UploadUrlRequest;
import dev.supavolt.contracts.Contracts.UploadUrlResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard surface: bucket management, keyed by org route and bucket id. */
@RestController
@RequestMapping("/orgs/{slug}/projects/{projectSlug}/storage")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Storage")
public class StorageController {

    private final ProjectResolver projects;
    private final StorageService storage;

    public StorageController(ProjectResolver projects, StorageService storage) {
        this.projects = projects;
        this.storage = storage;
    }

    private UUID project(String slug, String projectSlug) {
        return projects.byDashboardRoute(slug, projectSlug).getId();
    }

    @GetMapping("/buckets")
    public List<StorageBucketDto> buckets(@PathVariable String slug, @PathVariable String projectSlug) {
        return storage.listBuckets(project(slug, projectSlug));
    }

    @PostMapping("/buckets")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public StorageBucketDto createBucket(
            @PathVariable String slug, @PathVariable String projectSlug, @Valid @RequestBody CreateBucketRequest req) {
        return storage.createBucket(project(slug, projectSlug), req);
    }

    @DeleteMapping("/buckets/{bucketId}")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<Void> deleteBucket(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID bucketId) {
        storage.deleteBucket(project(slug, projectSlug), bucketId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/buckets/{bucketId}/objects")
    public List<StorageObjectDto> objects(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID bucketId) {
        return storage.listObjects(project(slug, projectSlug), bucketId);
    }

    @PostMapping("/buckets/{bucketId}/upload-url")
    public UploadUrlResponse uploadUrl(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID bucketId,
            @Valid @RequestBody UploadUrlRequest req) {
        return storage.createUploadUrl(project(slug, projectSlug), bucketId, req);
    }

    @PostMapping("/buckets/{bucketId}/objects")
    public StorageObjectDto register(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID bucketId,
            @Valid @RequestBody RegisterObjectRequest req) {
        return storage.registerObject(project(slug, projectSlug), bucketId, req);
    }

    @DeleteMapping("/objects/{objectId}")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID objectId) {
        storage.deleteObject(project(slug, projectSlug), objectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/objects/{objectId}/signed-url")
    public SignedUrlResponse signedUrl(
            @PathVariable String slug, @PathVariable String projectSlug, @PathVariable UUID objectId) {
        return storage.signedUrl(project(slug, projectSlug), objectId);
    }
}
