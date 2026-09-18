using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Shouldly;

namespace Supavolt.Tests.Integration;

[Collection(IntegrationCollection.Name)]
public class ApiTests(SupavoltFactory api)
{
    // ── Dashboard auth ───────────────────────────────────────────────────────

    [Fact]
    public async Task Register_then_me_returns_the_user()
    {
        var user = await api.NewUserAsync();

        var me = await user.Http.GetFromJsonAsync<JsonElement>("auth/me");
        me.GetProperty("email").GetString().ShouldBe(user.Email);
    }

    [Fact]
    public async Task Unauthenticated_me_is_401()
    {
        var res = await api.NewClient().Http.GetAsync("auth/me");
        res.StatusCode.ShouldBe(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Refresh_rotates_and_reuse_revokes_the_whole_chain()
    {
        var user = await api.NewUserAsync();
        var original = user.Cookie("refresh_token").ShouldNotBeNull();

        // First use rotates: a new refresh token is issued.
        (await user.Http.PostAsync("auth/refresh", null)).StatusCode.ShouldBe(HttpStatusCode.OK);
        var rotated = user.Cookie("refresh_token").ShouldNotBeNull();
        rotated.ShouldNotBe(original);

        // Presenting the old token again is reuse: rejected...
        (await RefreshWith(original)).StatusCode.ShouldBe(HttpStatusCode.Unauthorized);

        // ...and the rotated token, never used, is now dead too.
        (await RefreshWith(rotated)).StatusCode.ShouldBe(HttpStatusCode.Unauthorized);
    }

    private Task<HttpResponseMessage> RefreshWith(string refreshToken)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "auth/refresh");
        request.Headers.Add("Cookie", $"refresh_token={refreshToken}");
        return api.NewClient().Http.SendAsync(request);
    }

    // ── Org roles ────────────────────────────────────────────────────────────

    private async Task<(ApiClient Admin, ApiClient Developer)> OrgWithDeveloperAsync()
    {
        var admin = await api.NewUserAsync();
        var developer = await api.NewUserAsync();

        (await admin.Http.PostAsJsonAsync($"orgs/{admin.OrgSlug}/members/invite", new { email = developer.Email }))
            .StatusCode.ShouldBe(HttpStatusCode.OK);

        var token = api.Mail.InviteToken(developer.Email);
        (await developer.Http.GetAsync($"auth/invite/accept?token={token}")).StatusCode.ShouldBe(HttpStatusCode.Redirect);

        developer.OrgSlug = admin.OrgSlug;
        return (admin, developer);
    }

    [Fact]
    public async Task Invite_token_is_single_use()
    {
        var admin = await api.NewUserAsync();
        var invitee = await api.NewUserAsync();
        await admin.Http.PostAsJsonAsync($"orgs/{admin.OrgSlug}/members/invite", new { email = invitee.Email });
        var token = api.Mail.InviteToken(invitee.Email);

        (await invitee.Http.GetAsync($"auth/invite/accept?token={token}")).StatusCode.ShouldBe(HttpStatusCode.Redirect);
        (await invitee.Http.GetAsync($"auth/invite/accept?token={token}")).StatusCode.ShouldBe(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Invite_for_someone_else_is_forbidden()
    {
        var admin = await api.NewUserAsync();
        var invitee = await api.NewUserAsync();
        var intruder = await api.NewUserAsync();
        await admin.Http.PostAsJsonAsync($"orgs/{admin.OrgSlug}/members/invite", new { email = invitee.Email });

        var res = await intruder.Http.GetAsync($"auth/invite/accept?token={api.Mail.InviteToken(invitee.Email)}");
        res.StatusCode.ShouldBe(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task Developer_gets_403_on_an_admin_route_and_admin_gets_200_on_a_member_route()
    {
        var (admin, developer) = await OrgWithDeveloperAsync();

        (await developer.Http.PostAsJsonAsync($"orgs/{developer.OrgSlug}/members/invite", new { email = "x@example.com" }))
            .StatusCode.ShouldBe(HttpStatusCode.Forbidden);

        (await admin.Http.GetAsync($"orgs/{admin.OrgSlug}/members")).StatusCode.ShouldBe(HttpStatusCode.OK);
        (await developer.Http.GetAsync($"orgs/{developer.OrgSlug}/members")).StatusCode.ShouldBe(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Non_member_gets_403_on_an_org()
    {
        var owner = await api.NewUserAsync();
        var stranger = await api.NewUserAsync();

        (await stranger.Http.GetAsync($"orgs/{owner.OrgSlug}/projects")).StatusCode.ShouldBe(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task Demoting_the_last_admin_is_400()
    {
        var admin = await api.NewUserAsync();
        var members = await admin.Http.GetFromJsonAsync<JsonElement>($"orgs/{admin.OrgSlug}/members");
        var id = members[0].GetProperty("id").GetString();

        var res = await admin.Http.PatchAsJsonAsync($"orgs/{admin.OrgSlug}/members/{id}/role", new { role = "developer" });
        res.StatusCode.ShouldBe(HttpStatusCode.BadRequest);

        (await admin.Http.DeleteAsync($"orgs/{admin.OrgSlug}/members/{id}")).StatusCode.ShouldBe(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Admin_can_be_demoted_once_another_admin_exists()
    {
        var (admin, developer) = await OrgWithDeveloperAsync();
        var members = await admin.Http.GetFromJsonAsync<JsonElement>($"orgs/{admin.OrgSlug}/members");
        string IdOf(string email) => members.EnumerateArray()
            .Single(m => m.GetProperty("user").GetProperty("email").GetString() == email).GetProperty("id").GetString()!;

        (await admin.Http.PatchAsJsonAsync($"orgs/{admin.OrgSlug}/members/{IdOf(developer.Email)}/role", new { role = "admin" }))
            .StatusCode.ShouldBe(HttpStatusCode.OK);
        (await admin.Http.PatchAsJsonAsync($"orgs/{admin.OrgSlug}/members/{IdOf(admin.Email)}/role", new { role = "developer" }))
            .StatusCode.ShouldBe(HttpStatusCode.OK);
    }

    // ── Projects and keys ────────────────────────────────────────────────────

    private sealed record CreatedProject(string Id, string Slug, string Schema, string AnonKey, string ServiceKey);

    private static async Task<CreatedProject> CreateProjectAsync(ApiClient user, string name = "Demo")
    {
        var res = await user.Http.PostAsJsonAsync($"orgs/{user.OrgSlug}/projects", new { name });
        res.StatusCode.ShouldBe(HttpStatusCode.Created, await res.Content.ReadAsStringAsync());
        var body = await res.Content.ReadFromJsonAsync<JsonElement>();
        var project = body.GetProperty("project");
        var keys = body.GetProperty("keys");

        return new CreatedProject(
            project.GetProperty("id").GetString()!,
            project.GetProperty("slug").GetString()!,
            project.GetProperty("dbSchema").GetString()!,
            keys.GetProperty("anonKey").GetString()!,
            keys.GetProperty("serviceRoleKey").GetString()!);
    }

    private static async Task CreateTodosAsync(ApiClient user, CreatedProject p)
    {
        var res = await user.Http.PostAsJsonAsync($"orgs/{user.OrgSlug}/projects/{p.Slug}/tables", new
        {
            name = "todos",
            columns = new object[]
            {
                new { name = "id", type = "bigint", isNullable = false, isPrimaryKey = true },
                new { name = "title", type = "text", isNullable = false, isPrimaryKey = false },
            }
        });
        res.StatusCode.ShouldBe(HttpStatusCode.Created, await res.Content.ReadAsStringAsync());
    }

    private HttpRequestMessage WithKey(HttpMethod method, string url, string key, object? body = null)
    {
        var request = new HttpRequestMessage(method, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", key);
        if (body is not null) request.Content = JsonContent.Create(body);
        return request;
    }

    [Fact]
    public async Task Creating_a_project_creates_its_schema_and_auth_users()
    {
        var user = await api.NewUserAsync();
        var project = await CreateProjectAsync(user);

        var tables = await user.Http.GetFromJsonAsync<List<string>>($"orgs/{user.OrgSlug}/projects/{project.Slug}/tables");
        tables.ShouldNotBeNull().ShouldContain("auth_users");
        project.Schema.ShouldMatch("^proj_[0-9a-f]{8}$");
    }

    [Fact]
    public async Task Project_GET_never_returns_the_service_role_key()
    {
        var user = await api.NewUserAsync();
        var project = await CreateProjectAsync(user);

        var json = await user.Http.GetStringAsync($"orgs/{user.OrgSlug}/projects/{project.Slug}");
        json.ShouldNotContain(project.ServiceKey);
        json.ShouldNotContain("serviceRole", Case.Insensitive);
        json.ShouldNotContain("secret", Case.Insensitive);
    }

    [Fact]
    public async Task Key_for_project_A_is_403_against_project_B()
    {
        var user = await api.NewUserAsync();
        var a = await CreateProjectAsync(user, "A");
        var b = await CreateProjectAsync(user, "B");
        await CreateTodosAsync(user, b);

        var res = await api.NewClient().Http.SendAsync(WithKey(HttpMethod.Get, $"projects/{b.Slug}/rest/todos", a.ServiceKey));
        res.StatusCode.ShouldBe(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task Anon_key_reads_but_gets_403_on_write()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);
        await CreateTodosAsync(user, p);
        var http = api.NewClient().Http;

        (await http.SendAsync(WithKey(HttpMethod.Post, $"projects/{p.Slug}/rest/todos", p.ServiceKey, new { title = "first" })))
            .StatusCode.ShouldBe(HttpStatusCode.Created);

        var read = await http.SendAsync(WithKey(HttpMethod.Get, $"projects/{p.Slug}/rest/todos?id=eq.1", p.AnonKey));
        read.StatusCode.ShouldBe(HttpStatusCode.OK);
        (await read.Content.ReadFromJsonAsync<JsonElement>())[0].GetProperty("title").GetString().ShouldBe("first");

        (await http.SendAsync(WithKey(HttpMethod.Post, $"projects/{p.Slug}/rest/todos", p.AnonKey, new { title = "nope" })))
            .StatusCode.ShouldBe(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task Malformed_filter_is_400_not_the_whole_table()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);
        await CreateTodosAsync(user, p);

        var res = await api.NewClient().Http.SendAsync(WithKey(HttpMethod.Get, $"projects/{p.Slug}/rest/todos?id=lte100", p.AnonKey));
        res.StatusCode.ShouldBe(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Rotated_keys_stop_working()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);
        await CreateTodosAsync(user, p);

        (await user.Http.PostAsync($"orgs/{user.OrgSlug}/projects/{p.Slug}/keys/rotate", null)).EnsureSuccessStatusCode();

        var res = await api.NewClient().Http.SendAsync(WithKey(HttpMethod.Get, $"projects/{p.Slug}/rest/todos", p.AnonKey));
        res.StatusCode.ShouldBe(HttpStatusCode.Unauthorized);
    }

    // ── SQL editor ───────────────────────────────────────────────────────────

    private static Task<HttpResponseMessage> RunSql(ApiClient user, CreatedProject p, string sql) =>
        user.Http.PostAsJsonAsync($"orgs/{user.OrgSlug}/projects/{p.Slug}/sql", new { sql });

    [Fact]
    public async Task Sql_editor_runs_one_statement_and_rejects_two()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        var ok = await RunSql(user, p, "select 41 + 1 as answer");
        ok.StatusCode.ShouldBe(HttpStatusCode.OK);
        (await ok.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("rows")[0].GetProperty("answer").GetInt32().ShouldBe(42);

        (await RunSql(user, p, "select 1; select 2")).StatusCode.ShouldBe(HttpStatusCode.BadRequest);
        (await RunSql(user, p, "select $1, $2; select 3")).StatusCode.ShouldBe(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Sql_editor_shows_the_database_error_message()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        var res = await RunSql(user, p, "select * from no_such_table");
        res.StatusCode.ShouldBe(HttpStatusCode.BadRequest);
        (await res.Content.ReadAsStringAsync()).ShouldContain("no_such_table");
    }

    [Fact]
    public async Task Sql_editor_reports_rows_affected_for_writes()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);
        await CreateTodosAsync(user, p);

        var res = await RunSql(user, p, "insert into todos (title) values ('a'), ('b'), ('c')");
        var body = await res.Content.ReadFromJsonAsync<JsonElement>();
        body.GetProperty("rowCount").GetInt32().ShouldBe(3);
        body.GetProperty("command").GetString().ShouldBe("INSERT");
    }

    // The proof that least privilege works: the tenant role cannot see the control plane.
    [Theory]
    [InlineData("select * from public.users")]
    [InlineData("select * from public.projects")]
    [InlineData("select * from public.refresh_tokens")]
    public async Task Sql_editor_cannot_read_control_plane_tables(string sql)
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        var res = await RunSql(user, p, sql);
        res.IsSuccessStatusCode.ShouldBeFalse();
        (await res.Content.ReadAsStringAsync()).ShouldNotContain(user.Email);
    }

    // Each project's SQL runs as its own login role. With one shared role, any project could read,
    // write and drop every other project's tables by naming the schema.
    [Theory]
    [InlineData("select * from {0}.todos")]
    [InlineData("select * from {0}.auth_users")]
    [InlineData("insert into {0}.todos (title) values ('pwned')")]
    [InlineData("drop table {0}.todos")]
    [InlineData("create table {0}.planted (id int)")]
    [InlineData("set role {0}")]
    public async Task Sql_editor_cannot_touch_another_projects_schema(string template)
    {
        var attacker = await api.NewUserAsync();
        var mine = await CreateProjectAsync(attacker, "Mine");
        var owner = await api.NewUserAsync();
        var victim = await CreateProjectAsync(owner, "Victim");
        await CreateTodosAsync(owner, victim);
        (await RunSql(owner, victim, "insert into todos (title) values ('secret')")).EnsureSuccessStatusCode();

        var res = await RunSql(attacker, mine, string.Format(template, victim.Schema));
        res.IsSuccessStatusCode.ShouldBeFalse(await res.Content.ReadAsStringAsync());

        // And the victim's data is untouched.
        var check = await (await RunSql(owner, victim, "select title from todos")).Content.ReadFromJsonAsync<JsonElement>();
        check.GetProperty("rows")[0].GetProperty("title").GetString().ShouldBe("secret");
    }

    [Fact]
    public async Task Tables_created_in_the_sql_editor_are_reachable_by_the_data_api()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        (await RunSql(user, p, "create table notes (id int primary key, body text)")).EnsureSuccessStatusCode();
        (await RunSql(user, p, "insert into notes values (1, 'hello')")).EnsureSuccessStatusCode();

        var res = await api.NewClient().Http.SendAsync(WithKey(HttpMethod.Get, $"projects/{p.Slug}/rest/notes", p.AnonKey));
        res.StatusCode.ShouldBe(HttpStatusCode.OK, await res.Content.ReadAsStringAsync());
        (await res.Content.ReadFromJsonAsync<JsonElement>())[0].GetProperty("body").GetString().ShouldBe("hello");

        var write = await api.NewClient().Http.SendAsync(
            WithKey(HttpMethod.Post, $"projects/{p.Slug}/rest/notes", p.ServiceKey, new { id = 2, body = "via api" }));
        write.StatusCode.ShouldBe(HttpStatusCode.Created, await write.Content.ReadAsStringAsync());
    }

    // Before per-project roles, SQL-editor tables were owned by the shared tenant role. The first
    // SQL editor run after upgrading hands them to the project role; the data API must still work.
    [Fact]
    public async Task Legacy_tables_owned_by_the_shared_role_are_handed_over()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        await using (var admin = new Npgsql.NpgsqlConnection(api.AdminConnectionString))
        {
            await admin.OpenAsync();
            await using var setup = new Npgsql.NpgsqlCommand($"""
                UPDATE projects SET db_role_password = NULL WHERE slug = '{p.Slug}';
                GRANT CREATE ON SCHEMA {p.Schema} TO supavolt_tenant;
                SET ROLE supavolt_tenant;
                CREATE TABLE {p.Schema}.legacy (id int PRIMARY KEY, note text);
                INSERT INTO {p.Schema}.legacy VALUES (1, 'old');
                RESET ROLE;
                """, admin);
            await setup.ExecuteNonQueryAsync();
        }

        (await RunSql(user, p, "insert into legacy values (2, 'new')")).StatusCode.ShouldBe(HttpStatusCode.OK);
        (await RunSql(user, p, "alter table legacy add column extra text")).StatusCode.ShouldBe(HttpStatusCode.OK);

        var read = await api.NewClient().Http.SendAsync(WithKey(HttpMethod.Get, $"projects/{p.Slug}/rest/legacy?order=id", p.AnonKey));
        read.StatusCode.ShouldBe(HttpStatusCode.OK, await read.Content.ReadAsStringAsync());
        (await read.Content.ReadFromJsonAsync<JsonElement>()).GetArrayLength().ShouldBe(2);

        // And the shared role lost CREATE on the schema.
        var privilege = await (await RunSql(user, p,
            $"select has_schema_privilege('supavolt_tenant', '{p.Schema}', 'CREATE') as c")).Content.ReadFromJsonAsync<JsonElement>();
        privilege.GetProperty("rows")[0].GetProperty("c").GetBoolean().ShouldBeFalse();
    }

    [Fact]
    public async Task Sql_editor_survives_the_role_changing_its_own_password()
    {
        var user = await api.NewUserAsync();
        var p = await CreateProjectAsync(user);

        (await RunSql(user, p, $"alter role {p.Schema} password 'changed-from-inside'")).EnsureSuccessStatusCode();
        (await RunSql(user, p, "select 1")).StatusCode.ShouldBe(HttpStatusCode.OK);
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    [Fact]
    public async Task Deleting_another_projects_storage_object_is_404()
    {
        var user = await api.NewUserAsync();
        var owner = await CreateProjectAsync(user, "Owner");
        var other = await CreateProjectAsync(user, "Other");
        var baseUrl = $"orgs/{user.OrgSlug}/projects";

        var bucket = await (await user.Http.PostAsJsonAsync($"{baseUrl}/{owner.Slug}/storage/buckets",
            new { name = "files", access = "private" })).Content.ReadFromJsonAsync<JsonElement>();
        var bucketId = bucket.GetProperty("id").GetString();

        var created = await user.Http.PostAsJsonAsync($"{baseUrl}/{owner.Slug}/storage/buckets/{bucketId}/objects",
            new { name = "a.txt", size = 1, contentType = "text/plain", objectKey = $"{owner.Id}/{bucketId}/x/a.txt" });
        created.IsSuccessStatusCode.ShouldBeTrue(await created.Content.ReadAsStringAsync());
        var objectId = (await created.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString();

        (await user.Http.DeleteAsync($"{baseUrl}/{other.Slug}/storage/objects/{objectId}")).StatusCode.ShouldBe(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Registering_an_object_key_outside_the_project_prefix_is_400()
    {
        var user = await api.NewUserAsync();
        var mine = await CreateProjectAsync(user, "Mine");
        var theirs = await CreateProjectAsync(user, "Theirs");
        var baseUrl = $"orgs/{user.OrgSlug}/projects/{mine.Slug}/storage/buckets";

        var bucketId = (await (await user.Http.PostAsJsonAsync(baseUrl, new { name = "files", access = "private" }))
            .Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetString();

        var res = await user.Http.PostAsJsonAsync($"{baseUrl}/{bucketId}/objects",
            new { name = "a.txt", size = 1, contentType = "text/plain", objectKey = $"{theirs.Id}/{bucketId}/x/a.txt" });
        res.StatusCode.ShouldBe(HttpStatusCode.Forbidden);
    }
}
