using System.Security.Claims;
using System.Security.Cryptography;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Api.Infrastructure.Mail;
using Supavolt.Contracts;

namespace Supavolt.Api.Features.Members;

public sealed class MembersService(SupavoltDbContext db, TimeProvider clock)
{
    public Task<List<OrgMemberDto>> ListAsync(string orgSlug, CancellationToken ct) =>
        db.OrgMembers
            .Where(m => m.Org.Slug == orgSlug)
            .OrderBy(m => m.CreatedAt)
            .Select(m => new OrgMemberDto(
                m.Id, m.Role, m.CreatedAt,
                new OrgMemberUserDto(m.User.Id, m.User.Name, m.User.Email, m.User.AvatarUrl)))
            .ToListAsync(ct);

    public async Task<OrgMemberDto> UpdateRoleAsync(
        string orgSlug, Guid memberId, OrgRole role, CancellationToken ct)
    {
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        var member = await LoadForUpdateAsync(orgSlug, memberId, ct);

        if (member.Role == OrgRole.Admin && role != OrgRole.Admin)
            await EnsureNotLastAdminAsync(orgSlug, memberId, ct);

        member.Role = role;
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);

        return new OrgMemberDto(member.Id, member.Role, member.CreatedAt,
            new OrgMemberUserDto(member.User.Id, member.User.Name, member.User.Email, member.User.AvatarUrl));
    }

    public async Task RemoveAsync(string orgSlug, Guid memberId, CancellationToken ct)
    {
        await using var tx = await db.Database.BeginTransactionAsync(ct);

        var member = await LoadForUpdateAsync(orgSlug, memberId, ct);
        await EnsureNotLastAdminAsync(orgSlug, memberId, ct);

        member.RemovedAt = clock.GetUtcNow();
        await db.SaveChangesAsync(ct);
        await tx.CommitAsync(ct);
    }

    private async Task<OrgMember> LoadForUpdateAsync(string orgSlug, Guid memberId, CancellationToken ct) =>
        await db.OrgMembers
            .Include(m => m.User)
            .Where(m => m.Id == memberId && m.Org.Slug == orgSlug)
            .SingleOrDefaultAsync(ct)
        ?? throw AppException.NotFound("Member");

    /// <summary>
    /// Row-locks the admin rows before counting. The original counted without a lock, so two
    /// concurrent demotions could both pass the check and leave the org with no admin.
    /// </summary>
    private async Task EnsureNotLastAdminAsync(string orgSlug, Guid memberId, CancellationToken ct)
    {
        var adminIds = await db.Database
            .SqlQuery<Guid>($"""
                SELECT m.id AS "Value"
                FROM org_members m
                JOIN organizations o ON o.id = m.org_id
                WHERE o.slug = {orgSlug} AND m.role = 'Admin' AND m.removed_at IS NULL
                FOR UPDATE OF m
                """)
            .ToListAsync(ct);

        if (adminIds.Contains(memberId) && adminIds.Count == 1)
            throw AppException.BadRequest("Cannot remove or demote the last admin of an organization");
    }
}

public sealed class InviteService(
    SupavoltDbContext db,
    IEmailSender mail,
    IOptions<InviteOptions> inviteOptions,
    IOptions<ApiOptions> api,
    TimeProvider clock)
{
    public async Task SendAsync(string orgSlug, string email, Guid invitedBy, CancellationToken ct)
    {
        var normalized = email.Trim().ToLowerInvariant();

        var org = await db.Organizations.SingleOrDefaultAsync(o => o.Slug == orgSlug, ct)
            ?? throw AppException.NotFound("Organization");

        var alreadyMember = await db.OrgMembers
            .AnyAsync(m => m.OrgId == org.Id && m.User.Email == normalized, ct);

        if (alreadyMember)
            throw AppException.BadRequest("User is already a member of this organization");

        var raw = RandomNumberGenerator.GetHexString(48, lowercase: true);
        var now = clock.GetUtcNow();

        // Superseding an outstanding invite keeps one live token per email and org.
        await db.Invites
            .Where(i => i.OrgId == org.Id && i.Email == normalized && i.AcceptedAt == null && i.RevokedAt == null)
            .ExecuteUpdateAsync(s => s.SetProperty(i => i.RevokedAt, now), ct);

        // Only the hash is stored; the raw token lives in the emailed link.
        db.Invites.Add(new Invite
        {
            OrgId = org.Id,
            Email = normalized,
            InvitedByUserId = invitedBy,
            TokenHash = TokenService.Hash(raw),
            ExpiresAt = now.Add(inviteOptions.Value.Lifetime)
        });

        await db.SaveChangesAsync(ct);

        var link = $"{api.Value.BaseUrl}/auth/invite/accept?token={raw}";
        await mail.SendAsync(normalized,
            $"You've been invited to join {org.Name} on Supavolt",
            $"""
             <div style="font-family:sans-serif;max-width:480px;margin:0 auto">
               <h2>You're invited to join {org.Name}</h2>
               <p>Accept the invite to start collaborating.</p>
               <p><a href="{link}">Accept invite</a></p>
               <p style="color:#999;font-size:13px">This link expires in {inviteOptions.Value.Lifetime.TotalHours:0} hours and can be used once.</p>
             </div>
             """, ct);
    }

    /// <summary>Returns the org slug so the caller can redirect there.</summary>
    public async Task<string> AcceptAsync(string rawToken, Guid userId, CancellationToken ct)
    {
        var hash = TokenService.Hash(rawToken);
        var now = clock.GetUtcNow();

        // A superseded (revoked) or already-accepted invite is as invalid as an unknown one.
        var invite = await db.Invites
            .Include(i => i.Org)
            .SingleOrDefaultAsync(i => i.TokenHash == hash, ct);

        if (invite is null || invite.AcceptedAt is not null || invite.RevokedAt is not null || invite.ExpiresAt <= now)
            throw AppException.BadRequest("Invalid or expired invite link");

        var user = await db.Users.SingleAsync(u => u.Id == userId, ct);

        if (!string.Equals(user.Email, invite.Email, StringComparison.OrdinalIgnoreCase))
            throw AppException.Forbidden("This invite was sent to a different email address");

        var exists = await db.OrgMembers.AnyAsync(m => m.OrgId == invite.OrgId && m.UserId == userId, ct);
        if (!exists)
            db.OrgMembers.Add(new OrgMember { OrgId = invite.OrgId, UserId = userId, Role = OrgRole.Developer });

        invite.AcceptedAt = now;
        await db.SaveChangesAsync(ct);

        return invite.Org.Slug;
    }
}

public static class MembersEndpoints
{
    public static IEndpointRouteBuilder MapMembers(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/orgs/{slug}/members")
            .WithTags("Members")
            .RequireAuthorization(Policies.OrgMember);

        group.MapGet("/", (string slug, MembersService svc, CancellationToken ct) =>
            svc.ListAsync(slug, ct));

        group.MapPatch("/{memberId:guid}/role", (
                string slug, Guid memberId, UpdateRoleRequest req, MembersService svc, CancellationToken ct) =>
                svc.UpdateRoleAsync(slug, memberId, req.Role, ct))
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapDelete("/{memberId:guid}", async (
                string slug, Guid memberId, MembersService svc, CancellationToken ct) =>
            {
                await svc.RemoveAsync(slug, memberId, ct);
                return TypedResults.Ok(new MessageResponse("Member removed"));
            })
            .RequireAuthorization(Policies.OrgAdmin);

        group.MapPost("/invite", async (
                string slug, InviteMemberRequest req, ClaimsPrincipal user,
                InviteService invites, CancellationToken ct) =>
            {
                await invites.SendAsync(slug, req.Email, user.UserId(), ct);
                return TypedResults.Ok(new MessageResponse("Invite sent"));
            })
            .RequireAuthorization(Policies.OrgAdmin);

        return app;
    }
}
