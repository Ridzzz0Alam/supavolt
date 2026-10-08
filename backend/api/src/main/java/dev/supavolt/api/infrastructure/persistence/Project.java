package dev.supavolt.api.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import dev.supavolt.api.common.Tokens;
import org.hibernate.annotations.ColumnTransformer;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A project: one Postgres schema, its keys, and its end-user auth settings.
 */
@Entity
@Table(name = "projects")
public class Project extends BaseEntity {

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "name")
    private String name;

    @Column(name = "slug")
    private String slug;

    /** Always proj_<8 hex>. Generated here, never accepted from a caller. */
    @Column(name = "db_schema")
    private String dbSchema;

    @Column(name = "project_url")
    private String projectUrl;

    /** Anon key in full: it is a public credential by design. */
    @Column(name = "anon_key")
    private String anonKey;

    /** SHA-256 of the service-role key. Plaintext is returned once, at creation or rotation. */
    @Column(name = "service_role_key_hash")
    private String serviceRoleKeyHash;

    /** Bumped on rotation; carried as a claim so old keys stop validating. */
    @Column(name = "key_version")
    private int keyVersion = 1;

    /** Signs end-user tokens for this project only. */
    @Column(name = "auth_jwt_secret")
    @Convert(converter = Converters.Encrypted.class)
    private String authJwtSecret;

    /**
     * Password of this project's own Postgres login role, which the SQL editor connects as.
     * Null for projects created before per-project roles; filled on first use.
     */
    @Column(name = "db_role_password")
    @Convert(converter = Converters.Encrypted.class)
    private String dbRolePassword;

    @Column(name = "site_url")
    private String siteUrl;

    @Column(name = "redirect_urls", columnDefinition = "jsonb")
    @Convert(converter = Converters.StringList.class)
    @ColumnTransformer(write = "?::jsonb")
    private List<String> redirectUrls = new ArrayList<>();

    @Column(name = "google_client_id")
    private String googleClientId;

    /** Encrypted at rest. */
    @Column(name = "google_client_secret")
    @Convert(converter = Converters.Encrypted.class)
    private String googleClientSecret;

    @Column(name = "github_client_id")
    private String githubClientId;

    /** Encrypted at rest. */
    @Column(name = "github_client_secret")
    @Convert(converter = Converters.Encrypted.class)
    private String githubClientSecret;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    /**
     * A signing secret the .NET API encrypted cannot be decrypted here (see SecretProtector) and
     * loads as null. Replace it on load, so any later save writes a valid row; the project's
     * existing end-user tokens stop validating, which they would have anyway.
     */
    @PostLoad
    void replaceUnreadableSecret() {
        if (authJwtSecret != null) return;
        authJwtSecret = Tokens.randomHex(64);
        secretReplaced = true;
    }

    @Transient
    private boolean secretReplaced;

    /** True when the signing secret was regenerated on load and has not been saved yet. */
    public boolean secretReplaced() {
        return secretReplaced;
    }

    @PrePersist
    void stampCreated() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getDbSchema() {
        return dbSchema;
    }

    public void setDbSchema(String dbSchema) {
        this.dbSchema = dbSchema;
    }

    public String getProjectUrl() {
        return projectUrl;
    }

    public void setProjectUrl(String projectUrl) {
        this.projectUrl = projectUrl;
    }

    public String getAnonKey() {
        return anonKey;
    }

    public void setAnonKey(String anonKey) {
        this.anonKey = anonKey;
    }

    public String getServiceRoleKeyHash() {
        return serviceRoleKeyHash;
    }

    public void setServiceRoleKeyHash(String serviceRoleKeyHash) {
        this.serviceRoleKeyHash = serviceRoleKeyHash;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(int keyVersion) {
        this.keyVersion = keyVersion;
    }

    public String getAuthJwtSecret() {
        return authJwtSecret;
    }

    public void setAuthJwtSecret(String authJwtSecret) {
        this.authJwtSecret = authJwtSecret;
    }

    public String getDbRolePassword() {
        return dbRolePassword;
    }

    public void setDbRolePassword(String dbRolePassword) {
        this.dbRolePassword = dbRolePassword;
    }

    public String getSiteUrl() {
        return siteUrl;
    }

    public void setSiteUrl(String siteUrl) {
        this.siteUrl = siteUrl;
    }

    public List<String> getRedirectUrls() {
        return redirectUrls;
    }

    public void setRedirectUrls(List<String> redirectUrls) {
        this.redirectUrls = redirectUrls;
    }

    public String getGoogleClientId() {
        return googleClientId;
    }

    public void setGoogleClientId(String googleClientId) {
        this.googleClientId = googleClientId;
    }

    public String getGoogleClientSecret() {
        return googleClientSecret;
    }

    public void setGoogleClientSecret(String googleClientSecret) {
        this.googleClientSecret = googleClientSecret;
    }

    public String getGithubClientId() {
        return githubClientId;
    }

    public void setGithubClientId(String githubClientId) {
        this.githubClientId = githubClientId;
    }

    public String getGithubClientSecret() {
        return githubClientSecret;
    }

    public void setGithubClientSecret(String githubClientSecret) {
        this.githubClientSecret = githubClientSecret;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
