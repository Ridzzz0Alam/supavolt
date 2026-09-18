using System.Text.Json.Serialization;
using System.Threading.RateLimiting;
using Amazon.Runtime;
using Amazon.S3;
using AspNet.Security.OAuth.GitHub;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.Google;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using Scalar.AspNetCore;
using Serilog;
using Slugify;
using Supavolt.Api.Common;
using Supavolt.Api.Features.Auth;
using Supavolt.Api.Features.DataApi;
using Supavolt.Api.Features.Members;
using Supavolt.Api.Features.Orgs;
using Supavolt.Api.Features.ProjectAuth;
using Supavolt.Api.Features.Projects;
using Supavolt.Api.Features.Realtime;
using Supavolt.Api.Features.SqlEditor;
using Supavolt.Api.Features.Storage;
using Supavolt.Api.Features.TableEditor;
using Supavolt.Api.Infrastructure;
using Supavolt.Api.Infrastructure.Configuration;
using Supavolt.Api.Infrastructure.Mail;
using Supavolt.Api.Infrastructure.Tenancy;

// Postgres columns are snake_case; the Dapper row records are PascalCase positional records.
Dapper.DefaultTypeMap.MatchNamesWithUnderscores = true;

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseSerilog((ctx, cfg) => cfg.ReadFrom.Configuration(ctx.Configuration).WriteTo.Console());

// ── Options: every one validated at boot, so a missing secret fails fast ─────

builder.Services.AddOptions<DatabaseOptions>().BindConfiguration(DatabaseOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<JwtOptions>().BindConfiguration(JwtOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<ProjectKeyOptions>().BindConfiguration(ProjectKeyOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<InviteOptions>().BindConfiguration(InviteOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<ApiOptions>().BindConfiguration(ApiOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<WebOptions>().BindConfiguration(WebOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<MailOptions>().BindConfiguration(MailOptions.Section)
    .ValidateDataAnnotations().ValidateOnStart();
builder.Services.AddOptions<StorageOptions>().BindConfiguration(StorageOptions.Section)
    .ValidateDataAnnotations();

var jwt = builder.Configuration.GetSection(JwtOptions.Section).Get<JwtOptions>()!;
var web = builder.Configuration.GetSection(WebOptions.Section).Get<WebOptions>()!;
var database = builder.Configuration.GetSection(DatabaseOptions.Section).Get<DatabaseOptions>()!;

// ── Platform services ────────────────────────────────────────────────────────

builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddHttpContextAccessor();
builder.Services.AddMemoryCache();
builder.Services.AddDataProtection();
builder.Services.AddProblemDetails();
builder.Services.AddExceptionHandler<AppExceptionHandler>();
builder.Services.AddSingleton<ISlugHelper>(new SlugHelper());
builder.Services.AddSingleton<IPasswordHasher<User>, PasswordHasher<User>>();

builder.Services.ConfigureHttpJsonOptions(o =>
{
    // camelCase matches what the existing TypeScript types expect.
    o.SerializerOptions.PropertyNamingPolicy = System.Text.Json.JsonNamingPolicy.CamelCase;
    o.SerializerOptions.Converters.Add(new JsonStringEnumConverter(System.Text.Json.JsonNamingPolicy.CamelCase));
    o.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull;
});

builder.Services.AddDbContext<SupavoltDbContext>(o => o
    .UseNpgsql(database.ConnectionString)
    .UseSnakeCaseNamingConvention());

builder.Services.AddSingleton<ITenantConnectionFactory, TenantConnectionFactory>();
builder.Services.AddScoped<ISchemaProvisioner, SchemaProvisioner>();

// Feature services, one line per slice.
builder.Services.AddScoped<TokenService>();
builder.Services.AddSingleton<ProjectKeyService>();
builder.Services.AddScoped<AuthService>();
builder.Services.AddScoped<OrgsService>();
builder.Services.AddScoped<MembersService>();
builder.Services.AddScoped<InviteService>();
builder.Services.AddScoped<ProjectsService>();
builder.Services.AddScoped<ProjectResolver>();
builder.Services.AddScoped<TableEditorService>();
builder.Services.AddScoped<SqlEditorService>();
builder.Services.AddScoped<DataApiService>();
builder.Services.AddScoped<TriggerService>();
builder.Services.AddScoped<StorageService>();
builder.Services.AddScoped<ProjectAuthService>();

// Mail: no API key configured means the console sender, so development never sends real mail.
var mailOptions = builder.Configuration.GetSection(MailOptions.Section).Get<MailOptions>();
if (string.IsNullOrWhiteSpace(mailOptions?.ApiKey))
    builder.Services.AddSingleton<IEmailSender, ConsoleEmailSender>();
else
    builder.Services.AddHttpClient<IEmailSender, HttpEmailSender>();

// Object storage: works against S3 or any S3-compatible endpoint such as Cloudflare R2.
builder.Services.AddSingleton<IAmazonS3>(sp =>
{
    var o = sp.GetRequiredService<IOptions<StorageOptions>>().Value;
    var config = new AmazonS3Config { ForcePathStyle = true };

    if (!string.IsNullOrWhiteSpace(o.ServiceUrl)) config.ServiceURL = o.ServiceUrl;
    else config.RegionEndpoint = Amazon.RegionEndpoint.GetBySystemName(o.Region);

    return new AmazonS3Client(new BasicAWSCredentials(o.AccessKeyId, o.SecretAccessKey), config);
});
builder.Services.AddSingleton<IObjectStore, S3ObjectStore>();

// ── Authentication: two schemes, plus a transient cookie for OAuth round-trips ──

var authentication = builder.Services.AddAuthentication("Dashboard")
    .AddJwtBearer("Dashboard", o =>
    {
        o.MapInboundClaims = false;
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidIssuer = jwt.Issuer,
            ValidAudience = jwt.Audience,
            IssuerSigningKey = new SymmetricSecurityKey(TokenService.KeyBytes(jwt.AccessSecret)),
            ClockSkew = TimeSpan.FromSeconds(30)
        };
        o.Events = new JwtBearerEvents
        {
            // The dashboard holds its token in an HttpOnly cookie, never in a header.
            OnMessageReceived = ctx =>
            {
                ctx.Token = ctx.Request.Cookies[AuthCookies.AccessToken];
                return Task.CompletedTask;
            }
        };
    })
    .AddScheme<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions, ProjectKeyAuthenticationHandler>(
        ProjectKeyAuthenticationHandler.SchemeName, null)
    .AddCookie(CookieAuthenticationDefaults.AuthenticationScheme, o =>
    {
        o.Cookie.Name = "supavolt_external";
        o.Cookie.SameSite = SameSiteMode.Lax;
        o.ExpireTimeSpan = TimeSpan.FromMinutes(10);
    });

// Remote handlers validate their options on every request, so an unconfigured provider would
// fail all traffic. Register each one only when its credentials are present.
if (!string.IsNullOrEmpty(builder.Configuration["Google:ClientId"]))
{
    authentication.AddGoogle(o =>
    {
        o.ClientId = builder.Configuration["Google:ClientId"]!;
        o.ClientSecret = builder.Configuration["Google:ClientSecret"] ?? "";
        o.SignInScheme = CookieAuthenticationDefaults.AuthenticationScheme;
        o.CallbackPath = "/api/auth/google/callback";
        o.ClaimActions.MapJsonKey("urn:supavolt:avatar", "picture");
        o.UsePkce = true;
    });
}

if (!string.IsNullOrEmpty(builder.Configuration["GitHub:ClientId"]))
{
    authentication.AddGitHub(o =>
    {
        o.ClientId = builder.Configuration["GitHub:ClientId"]!;
        o.ClientSecret = builder.Configuration["GitHub:ClientSecret"] ?? "";
        o.SignInScheme = CookieAuthenticationDefaults.AuthenticationScheme;
        o.CallbackPath = "/api/auth/github/callback";
        o.Scope.Add("user:email");
        o.ClaimActions.MapJsonKey("urn:supavolt:avatar", "avatar_url");
    });
}

builder.Services.AddSingleton<IAuthorizationHandler, OrgRoleHandler>();

builder.Services.AddAuthorizationBuilder()
    .SetDefaultPolicy(new AuthorizationPolicyBuilder("Dashboard").RequireAuthenticatedUser().Build())
    .AddPolicy(Policies.OrgMember, p =>
    {
        p.AuthenticationSchemes.Add("Dashboard");
        p.RequireAuthenticatedUser();
        p.Requirements.Add(new OrgRoleRequirement(null));
    })
    .AddPolicy(Policies.OrgAdmin, p =>
    {
        p.AuthenticationSchemes.Add("Dashboard");
        p.RequireAuthenticatedUser();
        p.Requirements.Add(new OrgRoleRequirement(Supavolt.Contracts.OrgRole.Admin));
    })
    .AddPolicy(Policies.ProjectKeyAny, p =>
    {
        p.AuthenticationSchemes.Add(ProjectKeyAuthenticationHandler.SchemeName);
        p.RequireAuthenticatedUser();
    })
    .AddPolicy(Policies.ProjectServiceRole, p =>
    {
        p.AuthenticationSchemes.Add(ProjectKeyAuthenticationHandler.SchemeName);
        p.RequireAuthenticatedUser();
        p.RequireClaim(ProjectKeyClaims.Role, "service_role");
    });

// ── Rate limiting: the original had none on sign-in or magic links ───────────

builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;

    // Configurable so integration tests, which all share one "unknown" client address, can raise it.
    var authPermits = builder.Configuration.GetValue("RateLimits:AuthPermitsPerMinute", 10);

    o.AddPolicy(RateLimits.Auth, http => RateLimitPartition.GetFixedWindowLimiter(
        http.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = authPermits, Window = TimeSpan.FromMinutes(1) }));

    o.AddPolicy(RateLimits.ProjectAuth, http => RateLimitPartition.GetFixedWindowLimiter(
        $"{http.Request.RouteValues["projectSlug"]}:{http.Connection.RemoteIpAddress}",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 20, Window = TimeSpan.FromMinutes(1) }));
});

builder.Services.AddCors(o => o.AddPolicy("dashboard", p => p
    .WithOrigins(web.Url)
    .AllowAnyHeader()
    .AllowAnyMethod()
    .AllowCredentials()));   // AllowAnyOrigin is illegal with credentials

// SignalR has its own serializer settings; match the HTTP ones so event types arrive as strings.
builder.Services.AddSignalR().AddJsonProtocol(o =>
    o.PayloadSerializerOptions.Converters.Add(new JsonStringEnumConverter(System.Text.Json.JsonNamingPolicy.CamelCase)));
builder.Services.AddHostedService<NotificationListener>();
builder.Services.AddOpenApi();

var app = builder.Build();

// ── Pipeline ────────────────────────────────────────────────────────────────

// Request logging wraps the exception handler, so it records the status the client actually got.
app.UseSerilogRequestLogging();
app.UseExceptionHandler();
app.UsePathBase("/api");
app.UseRouting();
app.UseCors("dashboard");
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/health", () => new { status = "ok", timestamp = DateTimeOffset.UtcNow }).AllowAnonymous();

app.MapAuth();
app.MapOrgs();
app.MapMembers();
app.MapProjects();
app.MapTableEditor();
app.MapSqlEditor();
app.MapDataApi();
app.MapRealtime();
app.MapStorage();
app.MapProjectStorage();
app.MapProjectAuth();
app.MapProjectAuthDashboard();

// The hub sits outside the /api path base, matching the existing frontend's URL logic.
app.MapHub<RealtimeHub>("/realtime");

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
    app.MapScalarApiReference();
}

// Migrations stay a deploy step; only the shared trigger function is ensured at boot.
using (var scope = app.Services.CreateScope())
{
    var provisioner = scope.ServiceProvider.GetRequiredService<ISchemaProvisioner>();
    await provisioner.EnsureInternalObjectsAsync();
}

app.Run();
