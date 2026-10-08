package dev.supavolt.api.features.projects;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.features.auth.ProjectKey;
import dev.supavolt.api.infrastructure.persistence.Project;
import dev.supavolt.api.infrastructure.persistence.ProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared by every project-scoped feature: resolves a project from either a dashboard route
 * (org slug + project slug) or a project key, and asserts the key matches the URL.
 */
@Component
public class ProjectResolver {

    private final ProjectRepository projects;

    public ProjectResolver(ProjectRepository projects) {
        this.projects = projects;
    }

    public Project byDashboardRoute(String orgSlug, String projectSlug) {
        return projects.findByOrgSlugAndSlug(orgSlug, projectSlug).orElseThrow(() -> AppException.notFound("Project"));
    }

    /**
     * Validates three things the original checked only partly: the project exists, the key's
     * project matches the slug in the URL, and the key version is current.
     */
    public Project byKey(ProjectKey key, String projectSlug) {
        return byKey(key.projectId(), key.keyVersion(), projectSlug);
    }

    public Project byKey(UUID projectId, int keyVersion, String projectSlug) {
        var project = projects.findById(projectId).orElseThrow(() -> AppException.notFound("Project"));

        if (!project.getSlug().equals(projectSlug))
            throw AppException.forbidden("API key does not match this project URL");

        if (project.getKeyVersion() != keyVersion)
            throw AppException.unauthorized("API key has been rotated");

        return project;
    }

    /** For callers with no project slug in the route, such as the realtime endpoint. */
    public boolean isCurrentKey(UUID projectId, int keyVersion) {
        return projects.existsByIdAndKeyVersion(projectId, keyVersion);
    }
}
