package dev.supavolt.api.features.projects;

import dev.supavolt.api.features.auth.OrgAccess;
import dev.supavolt.contracts.Contracts.CreateProjectRequest;
import dev.supavolt.contracts.Contracts.CreatedProjectResponse;
import dev.supavolt.contracts.Contracts.ProjectDto;
import dev.supavolt.contracts.Contracts.ProjectKeysResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{slug}/projects")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Projects")
public class ProjectsController {

    private final ProjectsService projects;

    public ProjectsController(ProjectsService projects) {
        this.projects = projects;
    }

    @GetMapping
    public List<ProjectDto> list(@PathVariable String slug) {
        return projects.list(slug);
    }

    @GetMapping("/{projectSlug}")
    public ProjectDto get(@PathVariable String slug, @PathVariable String projectSlug) {
        return projects.get(slug, projectSlug);
    }

    /** The service-role key in the response is the only time it is ever readable. */
    @PostMapping
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ResponseEntity<CreatedProjectResponse> create(
            @PathVariable String slug, @Valid @RequestBody CreateProjectRequest req) {
        var created = projects.create(OrgAccess.currentOrgId(), req);
        return ResponseEntity.created(URI.create(created.project().projectUrl())).body(created);
    }

    @PostMapping("/{projectSlug}/keys/rotate")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ProjectKeysResponse rotateKeys(@PathVariable String slug, @PathVariable String projectSlug) {
        return projects.rotateKeys(slug, projectSlug);
    }
}
