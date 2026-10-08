package dev.supavolt.api.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One SQL editor run.
 */
@Entity
@Table(name = "query_history")
public class QueryHistoryItem extends BaseEntity {

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "sql")
    private String sql;

    @Column(name = "execution_time_ms")
    private int executionTimeMs;

    @Column(name = "row_count")
    private long rowCount;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    void stampCreated() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public int getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(int executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public long getRowCount() {
        return rowCount;
    }

    public void setRowCount(long rowCount) {
        this.rowCount = rowCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
