package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    @Query("select p from Project p join Organization o on o.id = p.orgId where o.slug = :orgSlug order by p.name")
    List<Project> findByOrgSlug(String orgSlug);

    @Query("select p from Project p join Organization o on o.id = p.orgId where o.slug = :orgSlug and p.slug = :slug")
    Optional<Project> findByOrgSlugAndSlug(String orgSlug, String slug);

    Optional<Project> findBySlug(String slug);

    boolean existsByIdAndKeyVersion(UUID id, int keyVersion);
}
