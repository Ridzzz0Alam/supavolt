package dev.supavolt.api.features.auth;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.infrastructure.persistence.OrgMemberRepository;
import dev.supavolt.contracts.Contracts.OrgRole;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The OrgMember and OrgAdmin policies, used as {@code @PreAuthorize("@orgAccess.member(#slug)")}.
 * Resolves the caller's active membership of the org in the {slug} route value and caches it on
 * the request, so a request that checks twice only queries once. Admin satisfies a Developer
 * requirement; the original compared roles for equality, which refused admins on developer routes.
 */
@Component("orgAccess")
public class OrgAccess {

    private static final String MEMBERSHIP = "supavolt.org_membership";

    private final OrgMemberRepository members;

    public OrgAccess(OrgMemberRepository members) {
        this.members = members;
    }

    public boolean member(String slug) {
        return check(slug, OrgRole.DEVELOPER);
    }

    public boolean admin(String slug) {
        return check(slug, OrgRole.ADMIN);
    }

    /** The org the current request was authorised against. */
    public static UUID currentOrgId() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null && attributes.getAttribute(MEMBERSHIP, RequestAttributes.SCOPE_REQUEST) instanceof Membership m)
            return m.orgId();

        throw AppException.forbidden("Organization context missing");
    }

    private boolean check(String slug, OrgRole minimum) {
        if (slug == null || slug.isEmpty()) return false;

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DashboardUser user)) return false;

        var membership = resolve(slug, user.id());
        return membership != null && membership.role().ordinal() >= minimum.ordinal();
    }

    private Membership resolve(String slug, UUID userId) {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null
                && attributes.getAttribute(MEMBERSHIP, RequestAttributes.SCOPE_REQUEST) instanceof Membership hit
                && hit.slug().equals(slug))
            return hit;

        var row = members.findMembership(slug, userId)
                .map(m -> new Membership(slug, m.getOrgId(), m.getRole()))
                .orElse(null);

        if (row != null && attributes != null)
            attributes.setAttribute(MEMBERSHIP, row, RequestAttributes.SCOPE_REQUEST);
        return row;
    }

    private record Membership(String slug, UUID orgId, OrgRole role) {
    }
}
