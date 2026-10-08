package dev.supavolt.api.features.orgs;

import dev.supavolt.api.features.auth.DashboardUser;
import dev.supavolt.contracts.Contracts.CreateOrgRequest;
import dev.supavolt.contracts.Contracts.OrganizationDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs")
@Tag(name = "Organizations")
public class OrgsController {

    private final OrgsService orgs;

    public OrgsController(OrgsService orgs) {
        this.orgs = orgs;
    }

    @GetMapping
    public List<OrganizationDto> list(@AuthenticationPrincipal DashboardUser user) {
        return orgs.listForUser(user.id());
    }

    @PostMapping
    public OrganizationDto create(@Valid @RequestBody CreateOrgRequest req, @AuthenticationPrincipal DashboardUser user) {
        return orgs.create(req, user.id());
    }

    @GetMapping("/{slug}")
    @PreAuthorize("@orgAccess.member(#slug)")
    public OrganizationDto get(@PathVariable String slug, @AuthenticationPrincipal DashboardUser user) {
        return orgs.get(slug, user.id());
    }
}
