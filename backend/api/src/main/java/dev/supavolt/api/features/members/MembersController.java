package dev.supavolt.api.features.members;

import dev.supavolt.api.features.auth.DashboardUser;
import dev.supavolt.contracts.Contracts.InviteMemberRequest;
import dev.supavolt.contracts.Contracts.MessageResponse;
import dev.supavolt.contracts.Contracts.OrgMemberDto;
import dev.supavolt.contracts.Contracts.UpdateRoleRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{slug}/members")
@PreAuthorize("@orgAccess.member(#slug)")
@Tag(name = "Members")
public class MembersController {

    private final MembersService members;
    private final InviteService invites;

    public MembersController(MembersService members, InviteService invites) {
        this.members = members;
        this.invites = invites;
    }

    @GetMapping
    public List<OrgMemberDto> list(@PathVariable String slug) {
        return members.list(slug);
    }

    @PatchMapping("/{memberId}/role")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public OrgMemberDto updateRole(
            @PathVariable String slug, @PathVariable UUID memberId, @Valid @RequestBody UpdateRoleRequest req) {
        return members.updateRole(slug, memberId, req.role());
    }

    @DeleteMapping("/{memberId}")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public MessageResponse remove(@PathVariable String slug, @PathVariable UUID memberId) {
        members.remove(slug, memberId);
        return new MessageResponse("Member removed");
    }

    @PostMapping("/invite")
    @PreAuthorize("@orgAccess.admin(#slug)")
    public MessageResponse invite(
            @PathVariable String slug, @Valid @RequestBody InviteMemberRequest req, @AuthenticationPrincipal DashboardUser user) {
        invites.send(slug, req.email(), user.id());
        return new MessageResponse("Invite sent");
    }
}
