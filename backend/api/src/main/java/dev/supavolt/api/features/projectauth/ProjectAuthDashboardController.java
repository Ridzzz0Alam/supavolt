package dev.supavolt.api.features.projectauth;

import dev.supavolt.contracts.Contracts.ProjectAuthUserDto;
import dev.supavolt.contracts.Contracts.ProjectOAuthSettingsDto;
import dev.supavolt.contracts.Contracts.UpdateOAuthSettingsRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard management of a project's end-users and provider settings. */
@RestController
@RequestMapping("/orgs/{slug}/projects/{projectSlug}/auth")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Project auth (dashboard)")
public class ProjectAuthDashboardController {

    private final ProjectAuthService auth;

    public ProjectAuthDashboardController(ProjectAuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/users")
    public List<ProjectAuthUserDto> users(@PathVariable String slug, @PathVariable String projectSlug) {
        return auth.listUsers(slug, projectSlug);
    }

    @GetMapping("/settings")
    public ProjectOAuthSettingsDto settings(@PathVariable String slug, @PathVariable String projectSlug) {
        return auth.getSettings(slug, projectSlug);
    }

    @PostMapping("/settings")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public ProjectOAuthSettingsDto updateSettings(
            @PathVariable String slug, @PathVariable String projectSlug, @RequestBody UpdateOAuthSettingsRequest req) {
        return auth.updateSettings(slug, projectSlug, req);
    }
}
