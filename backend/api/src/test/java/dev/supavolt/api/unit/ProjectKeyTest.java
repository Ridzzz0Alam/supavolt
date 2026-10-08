package dev.supavolt.api.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.features.auth.ProjectKeyService;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.persistence.Project;
import dev.supavolt.api.infrastructure.persistence.ProjectRepository;
import dev.supavolt.contracts.Contracts.ProjectKeyRole;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectKeyTest {

    private static ProjectKeyService keys(char fill) {
        var keyOptions = new SupavoltProperties.ProjectKeys(String.valueOf(fill).repeat(64), "supavolt-projects");
        var properties = new SupavoltProperties(null, null, keyOptions, null, null, null, null, null, null, null, null, null);
        return new ProjectKeyService(properties, Clock.systemUTC());
    }

    private static final ProjectKeyService KEYS = keys('k');

    @Test
    void anon_key_does_not_carry_the_service_role_claim() {
        assertThat(KEYS.validate(KEYS.sign(UUID.randomUUID(), ProjectKeyRole.ANON, 1)).serviceRole()).isFalse();
    }

    @Test
    void service_key_carries_the_service_role_claim() {
        assertThat(KEYS.validate(KEYS.sign(UUID.randomUUID(), ProjectKeyRole.SERVICE_ROLE, 1)).serviceRole()).isTrue();
    }

    @Test
    void key_carries_project_and_version() {
        var id = UUID.randomUUID();
        var key = KEYS.validate(KEYS.sign(id, ProjectKeyRole.ANON, 7));
        assertThat(key.projectId()).isEqualTo(id);
        assertThat(key.keyVersion()).isEqualTo(7);
    }

    @Test
    void key_signed_with_another_secret_is_rejected() {
        assertThat(KEYS.validate(keys('x').sign(UUID.randomUUID(), ProjectKeyRole.SERVICE_ROLE, 1))).isNull();
    }

    @Test
    void garbage_is_rejected() {
        assertThat(KEYS.validate("not-a-jwt")).isNull();
    }

    private static Project project(int keyVersion) {
        var project = new Project();
        project.setSlug("demo-" + UUID.randomUUID().toString().substring(0, 6));
        project.setKeyVersion(keyVersion);
        return project;
    }

    // Version 1 against a project at version 2: the resolver is the gate.
    @Test
    void key_signed_at_version_1_fails_against_a_project_at_version_2() {
        var project = project(2);
        var repository = mock(ProjectRepository.class);
        when(repository.findById(project.getId())).thenReturn(Optional.of(project));
        when(repository.existsByIdAndKeyVersion(project.getId(), 2)).thenReturn(true);
        var resolver = new ProjectResolver(repository);

        var key = KEYS.validate(KEYS.sign(project.getId(), ProjectKeyRole.SERVICE_ROLE, 1));

        try {
            resolver.byKey(key, project.getSlug());
            throw new AssertionError("Expected a 401");
        } catch (AppException e) {
            assertThat(e.status()).isEqualTo(401);
        }

        assertThat(resolver.isCurrentKey(project.getId(), 1)).isFalse();
        assertThat(resolver.isCurrentKey(project.getId(), 2)).isTrue();
    }

    @Test
    void key_for_another_project_slug_is_forbidden() {
        var project = project(1);
        var repository = mock(ProjectRepository.class);
        when(repository.findById(project.getId())).thenReturn(Optional.of(project));

        try {
            new ProjectResolver(repository).byKey(project.getId(), 1, "someone-elses-project");
            throw new AssertionError("Expected a 403");
        } catch (AppException e) {
            assertThat(e.status()).isEqualTo(403);
        }
    }
}
