package dev.supavolt.api.features.orgs;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Slugs;
import dev.supavolt.api.infrastructure.persistence.OrgMember;
import dev.supavolt.api.infrastructure.persistence.OrgMemberRepository;
import dev.supavolt.api.infrastructure.persistence.Organization;
import dev.supavolt.api.infrastructure.persistence.OrganizationRepository;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.CreateOrgRequest;
import dev.supavolt.contracts.Contracts.OrgRole;
import dev.supavolt.contracts.Contracts.OrganizationDto;
import dev.supavolt.api.infrastructure.persistence.Converters;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class OrgsService {

    /** One query with two correlated subqueries — no N+1, no GROUP BY over a left join. */
    private static final String SELECT = """
            SELECT o.id, o.name, o.slug, m.role, o.created_at,
                   (SELECT count(*) FROM projects p WHERE p.org_id = o.id) AS project_count,
                   (SELECT count(*) FROM org_members x WHERE x.org_id = o.id AND x.removed_at IS NULL) AS member_count
            FROM org_members m
            JOIN organizations o ON o.id = m.org_id
            WHERE m.user_id = :userId AND m.removed_at IS NULL
            """;

    private static final RowMapper<OrganizationDto> MAPPER = (rs, i) -> new OrganizationDto(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("slug"),
            new Converters.OrgRoleText().convertToEntityAttribute(rs.getString("role")),
            rs.getInt("project_count"),
            rs.getInt("member_count"),
            rs.getObject("created_at", OffsetDateTime.class));

    private final TenantConnections connections;
    private final OrganizationRepository organizations;
    private final OrgMemberRepository members;

    public OrgsService(TenantConnections connections, OrganizationRepository organizations, OrgMemberRepository members) {
        this.connections = connections;
        this.organizations = organizations;
        this.members = members;
    }

    public List<OrganizationDto> listForUser(UUID userId) {
        return connections.admin().sql(SELECT + " ORDER BY o.name")
                .param("userId", userId)
                .query(MAPPER).list();
    }

    public OrganizationDto get(String slug, UUID userId) {
        return connections.admin().sql(SELECT + " AND o.slug = :slug")
                .param("userId", userId)
                .param("slug", slug)
                .query(MAPPER).optional()
                .orElseThrow(() -> AppException.notFound("Organization"));
    }

    public OrganizationDto create(CreateOrgRequest req, UUID userId) {
        var org = new Organization();
        org.setName(req.name().trim());
        org.setSlug(Slugs.unique(req.name() + "-org"));
        organizations.save(org);

        var member = new OrgMember();
        member.setOrgId(org.getId());
        member.setUserId(userId);
        member.setRole(OrgRole.ADMIN);
        members.save(member);

        return new OrganizationDto(org.getId(), org.getName(), org.getSlug(), OrgRole.ADMIN, 0, 1, org.getCreatedAt());
    }
}
