package dev.supavolt.api.features.storage;

import dev.supavolt.api.features.auth.ProjectKey;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.contracts.Contracts.RegisterObjectRequest;
import dev.supavolt.contracts.Contracts.SignedUrlResponse;
import dev.supavolt.contracts.Contracts.StorageObjectDto;
import dev.supavolt.contracts.Contracts.UploadUrlRequest;
import dev.supavolt.contracts.Contracts.UploadUrlResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK surface: the same operations addressed by bucket name and authorised by project key. Reads
 * take any key; uploads and deletes need the service role (enforced in SecurityConfig).
 */
@RestController
@RequestMapping("/projects/{projectSlug}/storage")
@Tag(name = "Storage (SDK)")
public class ProjectStorageController {

    private final ProjectResolver projects;
    private final StorageService storage;

    public ProjectStorageController(ProjectResolver projects, StorageService storage) {
        this.projects = projects;
        this.storage = storage;
    }

    @GetMapping("/buckets/{bucketName}/objects")
    public List<StorageObjectDto> objects(
            @PathVariable String projectSlug, @PathVariable String bucketName, @AuthenticationPrincipal ProjectKey key) {
        var projectId = projects.byKey(key, projectSlug).getId();
        return storage.listObjects(projectId, storage.bucketIdByName(projectId, bucketName));
    }

    @PostMapping("/buckets/{bucketName}/upload-url")
    public UploadUrlResponse uploadUrl(
            @PathVariable String projectSlug, @PathVariable String bucketName,
            @Valid @RequestBody UploadUrlRequest req, @AuthenticationPrincipal ProjectKey key) {
        var projectId = projects.byKey(key, projectSlug).getId();
        return storage.createUploadUrl(projectId, storage.bucketIdByName(projectId, bucketName), req);
    }

    @PostMapping("/buckets/{bucketName}/objects")
    public StorageObjectDto register(
            @PathVariable String projectSlug, @PathVariable String bucketName,
            @Valid @RequestBody RegisterObjectRequest req, @AuthenticationPrincipal ProjectKey key) {
        var projectId = projects.byKey(key, projectSlug).getId();
        return storage.registerObject(projectId, storage.bucketIdByName(projectId, bucketName), req);
    }

    @DeleteMapping("/objects/{objectId}")
    public ResponseEntity<Void> deleteObject(
            @PathVariable String projectSlug, @PathVariable UUID objectId, @AuthenticationPrincipal ProjectKey key) {
        storage.deleteObject(projects.byKey(key, projectSlug).getId(), objectId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/objects/{objectId}/signed-url")
    public SignedUrlResponse signedUrl(
            @PathVariable String projectSlug, @PathVariable UUID objectId, @AuthenticationPrincipal ProjectKey key) {
        return storage.signedUrl(projects.byKey(key, projectSlug).getId(), objectId);
    }
}
