package dev.supavolt.api.features.projects;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Slugs;
import dev.supavolt.api.common.Tokens;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.features.auth.ProjectKeyService;
import dev.supavolt.api.infrastructure.persistence.Project;
import dev.supavolt.api.infrastructure.persistence.ProjectRepository;
import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.SchemaProvisioner;
import dev.supavolt.contracts.Contracts.CreateProjectRequest;
import dev.supavolt.contracts.Contracts.CreatedProjectResponse;
import dev.supavolt.contracts.Contracts.ProjectDto;
import dev.supavolt.contracts.Contracts.ProjectKeyRole;
import dev.supavolt.contracts.Contracts.ProjectKeysResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectsService {

    private final ProjectRepository projects;
    private final SchemaProvisioner provisioner;
    private final ProjectKeyService keys;
    private final SupavoltProperties properties;

    public ProjectsService(
            ProjectRepository projects, SchemaProvisioner provisioner, ProjectKeyService keys, SupavoltProperties properties) {
        this.projects = projects;
        this.provisioner = provisioner;
        this.keys = keys;
        this.properties = properties;
    }

    public List<ProjectDto> list(String orgSlug) {
        return projects.findByOrgSlug(orgSlug).stream().map(ProjectsService::toDto).toList();
    }

    public ProjectDto get(String orgSlug, String projectSlug) {
        return projects.findByOrgSlugAndSlug(orgSlug, projectSlug)
                .map(ProjectsService::toDto)
                .orElseThrow(() -> AppException.notFound("Project"));
    }

    /**
     * Schema creation is DDL and therefore not transactional with the row insert on every
     * Postgres setup, so the row is written first and the schema second: a failed provision
     * leaves an unusable project row to retry, never a schema with no owner.
     */
    public CreatedProjectResponse create(UUID orgId, CreateProjectRequest req) {
        var slug = Slugs.unique(req.name());
        var schema = SchemaNames.newProjectSchema();

        var project = new Project();
        project.setOrgId(orgId);
        project.setName(req.name().trim());
        project.setSlug(slug);
        project.setDbSchema(schema);
        project.setProjectUrl(properties.api().baseUrl() + "/projects/" + slug);
        project.setAuthJwtSecret(Tokens.randomHex(64));
        project.setKeyVersion(1);

        var issued = mintKeys(project);
        projects.save(project);

        project.setDbRolePassword(provisioner.provisionProject(schema));
        projects.save(project);

        return new CreatedProjectResponse(toDto(project), issued);
    }

    /** Bumping the key version invalidates every key issued before this call. */
    public ProjectKeysResponse rotateKeys(String orgSlug, String projectSlug) {
        var project = projects.findByOrgSlugAndSlug(orgSlug, projectSlug)
                .orElseThrow(() -> AppException.notFound("Project"));

        project.setKeyVersion(project.getKeyVersion() + 1);
        var issued = mintKeys(project);
        projects.save(project);

        return issued;
    }

    private ProjectKeysResponse mintKeys(Project project) {
        var anon = keys.sign(project.getId(), ProjectKeyRole.ANON, project.getKeyVersion());
        var service = keys.sign(project.getId(), ProjectKeyRole.SERVICE_ROLE, project.getKeyVersion());

        project.setAnonKey(anon);                               // public by design
        project.setServiceRoleKeyHash(Tokens.sha256(service));  // shown once, never stored in full

        return new ProjectKeysResponse(anon, service, project.getKeyVersion());
    }

    private static ProjectDto toDto(Project p) {
        return new ProjectDto(p.getId(), p.getOrgId(), p.getName(), p.getSlug(), p.getDbSchema(),
                p.getProjectUrl(), p.getAnonKey(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
