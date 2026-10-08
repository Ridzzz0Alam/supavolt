package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    @Query("select m from OrgMember m join Organization o on o.id = m.orgId where o.slug = :slug and m.userId = :userId")
    Optional<OrgMember> findMembership(String slug, UUID userId);

    @Query("select m from OrgMember m join Organization o on o.id = m.orgId where m.id = :id and o.slug = :slug")
    Optional<OrgMember> findInOrg(UUID id, String slug);

    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);

    @Query("select count(m) > 0 from OrgMember m join User u on u.id = m.userId where m.orgId = :orgId and u.email = :email")
    boolean existsByOrgIdAndEmail(UUID orgId, String email);
}
