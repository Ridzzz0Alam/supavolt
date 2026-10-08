package dev.supavolt.api.infrastructure.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

public interface QueryHistoryRepository extends JpaRepository<QueryHistoryItem, UUID> {

    @Query("select h from QueryHistoryItem h join Project p on p.id = h.projectId join Organization o on o.id = p.orgId"
            + " where p.slug = :projectSlug and o.slug = :orgSlug order by h.createdAt desc")
    List<QueryHistoryItem> history(String orgSlug, String projectSlug, Limit limit);
}
