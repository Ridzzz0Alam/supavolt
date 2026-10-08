package dev.supavolt.contracts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every request and response shape of the HTTP API. {@code frontend/lib/types.ts} mirrors this file
 * by hand, so keep the two in step. JSON is camelCase, and enums travel as lowercase strings.
 */
public final class Contracts {

    private Contracts() {
    }

    // ─── Enums ────────────────────────────────────────────────────────────────────
    // Declaration order matters for OrgRole: a later role satisfies an earlier one.

    public enum OrgRole { DEVELOPER, ADMIN }

    public enum ProjectKeyRole { ANON, SERVICE_ROLE }

    public enum BucketAccess { PUBLIC, PRIVATE }

    public enum AuthProvider { EMAIL, GOOGLE, GITHUB }

    /** The only column types the table editor will emit. Anything else is a 400. */
    public enum ColumnType { TEXT, INTEGER, BIGINT, BOOLEAN, TIMESTAMP, UUID, JSONB, NUMERIC }

    public enum FilterOperator { EQ, NEQ, GT, GTE, LT, LTE, LIKE, ILIKE, IS }

    public enum RealtimeEventType { INSERT, UPDATE, DELETE }

    // ─── Platform auth ────────────────────────────────────────────────────────────

    public record RegisterRequest(@NotBlank @Email String email, @NotBlank String password, @NotNull String name) {
    }

    public record LoginRequest(@NotNull String email, @NotNull String password) {
    }

    public record MessageResponse(String message) {
    }

    public record CurrentUserResponse(UUID id, String email, String name, String avatarUrl) {
    }

    /** Which dashboard sign-in providers the server has credentials for. */
    public record AuthProvidersResponse(boolean google, boolean github) {
    }

    // ─── Organizations and members ────────────────────────────────────────────────

    public record CreateOrgRequest(@NotBlank String name) {
    }

    public record OrganizationDto(
            UUID id,
            String name,
            String slug,
            OrgRole role,
            int projectCount,
            int memberCount,
            OffsetDateTime createdAt) {
    }

    public record OrgMemberDto(UUID id, OrgRole role, OffsetDateTime createdAt, OrgMemberUserDto user) {
    }

    public record OrgMemberUserDto(UUID id, String name, String email, String avatarUrl) {
    }

    public record InviteMemberRequest(@NotBlank @Email String email) {
    }

    public record UpdateRoleRequest(@NotNull OrgRole role) {
    }

    // ─── Projects ─────────────────────────────────────────────────────────────────

    public record CreateProjectRequest(@NotBlank String name) {
    }

    public record ProjectDto(
            UUID id,
            UUID orgId,
            String name,
            String slug,
            String dbSchema,
            String projectUrl,
            String anonKey,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    /** Returned once, at creation or rotation. The service role key is never readable again. */
    public record ProjectKeysResponse(String anonKey, String serviceRoleKey, int keyVersion) {
    }

    public record CreatedProjectResponse(ProjectDto project, ProjectKeysResponse keys) {
    }

    // ─── Table editor ─────────────────────────────────────────────────────────────

    public record ForeignKeyRef(String table, String column) {
    }

    public record TableColumnDto(
            String name,
            ColumnType type,
            boolean isNullable,
            boolean isPrimaryKey,
            String defaultValue,
            ForeignKeyRef foreignKey) {
    }

    public record TableInfoDto(String name, List<TableColumnDto> columns) {
    }

    public record TableRowsDto(List<Map<String, Object>> rows, long count) {
    }

    public record CreateColumnRequest(
            @NotNull String name,
            @NotNull ColumnType type,
            boolean isNullable,
            boolean isPrimaryKey,
            String defaultValue,
            String foreignKeyTable,
            String foreignKeyColumn) {
    }

    public record CreateTableRequest(@NotNull String name, @NotNull @Valid List<CreateColumnRequest> columns) {
    }

    public record AddColumnRequest(@NotNull String name, @NotNull ColumnType type, String defaultValue) {
    }

    public record UpdateRowRequest(@NotNull String pkColumn, @NotNull Map<String, Object> updates) {
    }

    // ─── SQL editor ───────────────────────────────────────────────────────────────

    public record ExecuteQueryRequest(@NotNull String sql) {
    }

    public record QueryResultDto(
            List<Map<String, Object>> rows,
            List<String> columns,
            long rowCount,
            long executionTimeMs,
            String command) {
    }

    public record QueryHistoryDto(UUID id, String sql, int executionTimeMs, long rowCount, OffsetDateTime createdAt) {
    }

    // ─── Realtime ─────────────────────────────────────────────────────────────────

    public record RealtimeEvent(
            RealtimeEventType type,
            String table,
            Map<String, Object> record,
            Map<String, Object> oldRecord,
            UUID projectId,
            OffsetDateTime timestamp) {
    }

    // ─── Storage ──────────────────────────────────────────────────────────────────

    public record CreateBucketRequest(@NotNull String name, @NotNull BucketAccess access) {
    }

    public record StorageBucketDto(UUID id, UUID projectId, String name, BucketAccess access, OffsetDateTime createdAt) {
    }

    public record StorageObjectDto(
            UUID id,
            UUID bucketId,
            String name,
            long size,
            String mimeType,
            String objectKey,
            String url,
            OffsetDateTime createdAt) {
    }

    public record UploadUrlRequest(@NotBlank String fileName, @NotBlank String contentType, long size) {
    }

    public record UploadUrlResponse(String uploadUrl, String objectKey, OffsetDateTime expiresAt) {
    }

    public record RegisterObjectRequest(
            @NotBlank String name, long size, @NotBlank String contentType, @NotBlank String objectKey) {
    }

    public record SignedUrlResponse(String url, OffsetDateTime expiresAt) {
    }

    // ─── Project (end-user) auth ──────────────────────────────────────────────────

    public record SignUpRequest(@NotBlank @Email String email, @NotEmpty String password) {
    }

    public record SignInRequest(@NotNull String email, @NotNull String password) {
    }

    public record MagicLinkRequest(@NotBlank @Email String email) {
    }

    public record ExchangeCodeRequest(@NotBlank String code) {
    }

    public record ProjectAuthUserDto(
            UUID id, String email, boolean emailVerified, AuthProvider provider, OffsetDateTime createdAt) {
    }

    public record ProjectAuthResponse(ProjectAuthUserDto user, String accessToken) {
    }

    public record ProjectOAuthSettingsDto(
            String siteUrl,
            String googleClientId,
            boolean googleConfigured,
            String githubClientId,
            boolean githubConfigured,
            List<String> redirectUrls) {
    }

    public record UpdateOAuthSettingsRequest(
            String siteUrl,
            String googleClientId,
            String googleClientSecret,
            String githubClientId,
            String githubClientSecret,
            List<String> redirectUrls) {
    }
}
