using System.Security.Claims;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.JsonWebTokens;
using Shouldly;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Contracts;

namespace Supavolt.Tests.Unit;

public class ProjectKeyTests
{
    private static readonly ProjectKeyService Keys = new(
        Options.Create(new ProjectKeyOptions { Secret = new string('k', 64), Issuer = "supavolt-projects" }),
        TimeProvider.System);

    private static async Task<ClaimsPrincipal> ValidateAsync(string token)
    {
        var result = await new JsonWebTokenHandler().ValidateTokenAsync(token, Keys.ValidationParameters());
        result.IsValid.ShouldBeTrue();
        return new ClaimsPrincipal(result.ClaimsIdentity);
    }

    [Fact]
    public async Task Anon_key_does_not_carry_the_service_role_claim()
    {
        var key = await ValidateAsync(Keys.Sign(Guid.NewGuid(), ProjectKeyRole.Anon, 1));
        key.IsServiceRole().ShouldBeFalse();
    }

    [Fact]
    public async Task Service_key_carries_the_service_role_claim()
    {
        var key = await ValidateAsync(Keys.Sign(Guid.NewGuid(), ProjectKeyRole.ServiceRole, 1));
        key.IsServiceRole().ShouldBeTrue();
    }

    [Fact]
    public async Task Key_carries_project_and_version()
    {
        var id = Guid.NewGuid();
        var key = await ValidateAsync(Keys.Sign(id, ProjectKeyRole.Anon, 7));
        key.ProjectId().ShouldBe(id);
        key.KeyVersion().ShouldBe(7);
    }

    [Fact]
    public async Task Key_signed_with_another_secret_is_rejected()
    {
        var other = new ProjectKeyService(
            Options.Create(new ProjectKeyOptions { Secret = new string('x', 64), Issuer = "supavolt-projects" }),
            TimeProvider.System);

        var result = await new JsonWebTokenHandler().ValidateTokenAsync(
            other.Sign(Guid.NewGuid(), ProjectKeyRole.ServiceRole, 1), Keys.ValidationParameters());

        result.IsValid.ShouldBeFalse();
    }

    // Version 1 against a project at version 2: the resolver is the gate, so exercise it directly
    // against an in-memory model rather than a database.
    [Fact]
    public async Task Key_signed_at_version_1_fails_against_a_project_at_version_2()
    {
        await using var db = TestDb.InMemory();
        var project = TestDb.AddProject(db, keyVersion: 2);
        var resolver = new ProjectResolver(db);

        var key = await ValidateAsync(Keys.Sign(project.Id, ProjectKeyRole.ServiceRole, 1));

        var ex = await Should.ThrowAsync<AppException>(() =>
            resolver.ByKeyAsync(key.ProjectId(), key.KeyVersion(), project.Slug, CancellationToken.None));
        ex.Status.ShouldBe(401);

        (await resolver.IsCurrentKeyAsync(project.Id, 1, CancellationToken.None)).ShouldBeFalse();
        (await resolver.IsCurrentKeyAsync(project.Id, 2, CancellationToken.None)).ShouldBeTrue();
    }

    [Fact]
    public async Task Key_for_another_project_slug_is_forbidden()
    {
        await using var db = TestDb.InMemory();
        var project = TestDb.AddProject(db, keyVersion: 1);

        var ex = await Should.ThrowAsync<AppException>(() =>
            new ProjectResolver(db).ByKeyAsync(project.Id, 1, "someone-elses-project", CancellationToken.None));
        ex.Status.ShouldBe(403);
    }
}
