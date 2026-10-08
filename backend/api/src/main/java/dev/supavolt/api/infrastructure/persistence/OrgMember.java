package dev.supavolt.api.infrastructure.persistence;

import dev.supavolt.contracts.Contracts.OrgRole;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Org membership. Soft-deleted: the restriction hides removed rows from every query, so no
 * query can forget "removed_at is null".
 */
@Entity
@Table(name = "org_members")
@SQLRestriction("removed_at IS NULL")
public class OrgMember extends BaseEntity {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "role")
    @Convert(converter = Converters.OrgRoleText.class)
    private OrgRole role = OrgRole.DEVELOPER;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;

    @PrePersist
    void stampCreated() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public OrgRole getRole() {
        return role;
    }

    public void setRole(OrgRole role) {
        this.role = role;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getRemovedAt() {
        return removedAt;
    }

    public void setRemovedAt(OffsetDateTime removedAt) {
        this.removedAt = removedAt;
    }
}
