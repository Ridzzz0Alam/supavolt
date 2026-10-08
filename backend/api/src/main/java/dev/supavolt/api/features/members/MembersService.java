package dev.supavolt.api.features.members;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.infrastructure.persistence.OrgMember;
import dev.supavolt.api.infrastructure.persistence.OrgMemberRepository;
import dev.supavolt.api.infrastructure.persistence.UserRepository;
import dev.supavolt.contracts.Contracts.OrgMemberDto;
import dev.supavolt.contracts.Contracts.OrgMemberUserDto;
import dev.supavolt.contracts.Contracts.OrgRole;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.api.infrastructure.persistence.Converters;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembersService {

    private final OrgMemberRepository members;
    private final UserRepository users;
    private final TenantConnections connections;
    private final Clock clock;

    public MembersService(OrgMemberRepository members, UserRepository users, TenantConnections connections, Clock clock) {
        this.members = members;
        this.users = users;
        this.connections = connections;
        this.clock = clock;
    }

    public List<OrgMemberDto> list(String orgSlug) {
        return connections.admin().sql("""
                        SELECT m.id, m.role, m.created_at, u.id AS user_id, u.name, u.email, u.avatar_url
                        FROM org_members m
                        JOIN organizations o ON o.id = m.org_id
                        JOIN users u ON u.id = m.user_id
                        WHERE o.slug = :slug AND m.removed_at IS NULL
                        ORDER BY m.created_at
                        """)
                .param("slug", orgSlug)
                .query((rs, i) -> new OrgMemberDto(
                        rs.getObject("id", UUID.class),
                        new Converters.OrgRoleText().convertToEntityAttribute(rs.getString("role")),
                        rs.getObject("created_at", OffsetDateTime.class),
                        new OrgMemberUserDto(
                                rs.getObject("user_id", UUID.class),
                                rs.getString("name"),
                                rs.getString("email"),
                                rs.getString("avatar_url"))))
                .list();
    }

    @Transactional
    public OrgMemberDto updateRole(String orgSlug, UUID memberId, OrgRole role) {
        var member = loadForUpdate(orgSlug, memberId);

        if (member.getRole() == OrgRole.ADMIN && role != OrgRole.ADMIN)
            ensureNotLastAdmin(orgSlug, memberId);

        member.setRole(role);
        members.save(member);

        var user = users.findById(member.getUserId()).orElseThrow(() -> AppException.notFound("Member"));
        return new OrgMemberDto(member.getId(), member.getRole(), member.getCreatedAt(),
                new OrgMemberUserDto(user.getId(), user.getName(), user.getEmail(), user.getAvatarUrl()));
    }

    @Transactional
    public void remove(String orgSlug, UUID memberId) {
        var member = loadForUpdate(orgSlug, memberId);
        ensureNotLastAdmin(orgSlug, memberId);

        member.setRemovedAt(OffsetDateTime.now(clock));
        members.save(member);
    }

    private OrgMember loadForUpdate(String orgSlug, UUID memberId) {
        return members.findInOrg(memberId, orgSlug).orElseThrow(() -> AppException.notFound("Member"));
    }

    /**
     * Row-locks the admin rows before counting. The original counted without a lock, so two
     * concurrent demotions could both pass the check and leave the org with no admin. Runs in
     * the caller's transaction: JdbcClient joins the JPA transaction on the same DataSource.
     */
    private void ensureNotLastAdmin(String orgSlug, UUID memberId) {
        var adminIds = connections.admin().sql("""
                        SELECT m.id
                        FROM org_members m
                        JOIN organizations o ON o.id = m.org_id
                        WHERE o.slug = :slug AND m.role = 'Admin' AND m.removed_at IS NULL
                        FOR UPDATE OF m
                        """)
                .param("slug", orgSlug)
                .query(UUID.class).list();

        if (adminIds.contains(memberId) && adminIds.size() == 1)
            throw AppException.badRequest("Cannot remove or demote the last admin of an organization");
    }
}
