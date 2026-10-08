package dev.supavolt.api.features.members;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Tokens;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.infrastructure.mail.EmailSender;
import dev.supavolt.api.infrastructure.persistence.Invite;
import dev.supavolt.api.infrastructure.persistence.InviteRepository;
import dev.supavolt.api.infrastructure.persistence.OrgMember;
import dev.supavolt.api.infrastructure.persistence.OrgMemberRepository;
import dev.supavolt.api.infrastructure.persistence.OrganizationRepository;
import dev.supavolt.api.infrastructure.persistence.UserRepository;
import dev.supavolt.contracts.Contracts.OrgRole;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
public class InviteService {

    private final OrganizationRepository organizations;
    private final OrgMemberRepository members;
    private final InviteRepository invites;
    private final UserRepository users;
    private final EmailSender mail;
    private final SupavoltProperties properties;
    private final Clock clock;

    public InviteService(
            OrganizationRepository organizations,
            OrgMemberRepository members,
            InviteRepository invites,
            UserRepository users,
            EmailSender mail,
            SupavoltProperties properties,
            Clock clock) {
        this.organizations = organizations;
        this.members = members;
        this.invites = invites;
        this.users = users;
        this.mail = mail;
        this.properties = properties;
        this.clock = clock;
    }

    public void send(String orgSlug, String email, UUID invitedBy) {
        var normalized = email.trim().toLowerCase(Locale.ROOT);

        var org = organizations.findBySlug(orgSlug).orElseThrow(() -> AppException.notFound("Organization"));

        if (members.existsByOrgIdAndEmail(org.getId(), normalized))
            throw AppException.badRequest("User is already a member of this organization");

        var raw = Tokens.randomHex(48);
        var now = OffsetDateTime.now(clock);
        var lifetime = properties.invites().lifetime();

        // Superseding an outstanding invite keeps one live token per email and org.
        invites.revokeOutstanding(org.getId(), normalized, now);

        // Only the hash is stored; the raw token lives in the emailed link.
        var invite = new Invite();
        invite.setOrgId(org.getId());
        invite.setEmail(normalized);
        invite.setInvitedByUserId(invitedBy);
        invite.setTokenHash(Tokens.sha256(raw));
        invite.setExpiresAt(now.plus(lifetime));
        invites.save(invite);

        var link = properties.api().baseUrl() + "/auth/invite/accept?token=" + raw;
        var orgName = HtmlUtils.htmlEscape(org.getName());

        mail.send(normalized, "You've been invited to join " + org.getName() + " on Supavolt", """
                <div style="font-family:sans-serif;max-width:480px;margin:0 auto">
                  <h2>You're invited to join %s</h2>
                  <p>Accept the invite to start collaborating.</p>
                  <p><a href="%s">Accept invite</a></p>
                  <p style="color:#999;font-size:13px">This link expires in %d hours and can be used once.</p>
                </div>
                """.formatted(orgName, link, lifetime.toHours()));
    }

    /** Returns the org slug so the caller can redirect there. */
    public String accept(String rawToken, UUID userId) {
        var now = OffsetDateTime.now(clock);

        // A superseded (revoked) or already-accepted invite is as invalid as an unknown one.
        var invite = invites.findByTokenHash(Tokens.sha256(rawToken)).orElse(null);

        if (invite == null || invite.getAcceptedAt() != null || invite.getRevokedAt() != null
                || !invite.getExpiresAt().isAfter(now))
            throw AppException.badRequest("Invalid or expired invite link");

        var user = users.findById(userId).orElseThrow(() -> AppException.unauthorized("Not authenticated"));

        if (!user.getEmail().equalsIgnoreCase(invite.getEmail()))
            throw AppException.forbidden("This invite was sent to a different email address");

        if (!members.existsByOrgIdAndUserId(invite.getOrgId(), userId)) {
            var member = new OrgMember();
            member.setOrgId(invite.getOrgId());
            member.setUserId(userId);
            member.setRole(OrgRole.DEVELOPER);
            members.save(member);
        }

        invite.setAcceptedAt(now);
        invites.save(invite);

        return organizations.findById(invite.getOrgId()).orElseThrow(() -> AppException.notFound("Organization")).getSlug();
    }
}
