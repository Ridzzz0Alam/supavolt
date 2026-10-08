package dev.supavolt.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

class ApiTest extends IntegrationTest {

    // ── Dashboard auth ───────────────────────────────────────────────────────

    @Test
    void register_then_me_returns_the_user() {
        var user = newUser();
        assertThat(user.get("auth/me").json().get("email").asString()).isEqualTo(user.email);
    }

    @Test
    void unauthenticated_me_is_401() {
        assertThat(newClient().get("auth/me").status()).isEqualTo(401);
    }

    @Test
    void wrong_password_is_401_with_problem_details() {
        var user = newUser();
        var res = newClient().post("auth/login", Map.of("email", user.email, "password", "nope"));
        assertThat(res.status()).isEqualTo(401);
        assertThat(res.json().get("title").asString()).isEqualTo("Invalid credentials");
    }

    @Test
    void duplicate_email_is_409_case_insensitively() {
        var user = newUser();
        var res = newClient().post("auth/register",
                Map.of("email", user.email.toUpperCase(), "password", "correct-horse-battery", "name", "Again"));
        assertThat(res.status()).isEqualTo(409);
    }

    @Test
    void refresh_rotates_and_reuse_revokes_the_whole_chain() {
        var user = newUser();
        var original = user.cookie("refresh_token");
        assertThat(original).isNotNull();

        // First use rotates: a new refresh token is issued.
        assertThat(user.post("auth/refresh", null).status()).isEqualTo(200);
        var rotated = user.cookie("refresh_token");
        assertThat(rotated).isNotNull().isNotEqualTo(original);

        // Presenting the old token again is reuse: rejected...
        assertThat(refreshWith(original).status()).isEqualTo(401);

        // ...and the rotated token, never used, is now dead too.
        assertThat(refreshWith(rotated).status()).isEqualTo(401);
    }

    private ApiClient.Response refreshWith(String refreshToken) {
        var client = newClient();
        return client.send(ApiClient.withBody(client.request("auth/refresh"), "POST", null)
                .header("Cookie", "refresh_token=" + refreshToken));
    }

    @Test
    void logout_clears_the_cookies_and_revokes_refresh() {
        var user = newUser();
        var refresh = user.cookie("refresh_token");

        assertThat(user.post("auth/logout", null).status()).isEqualTo(200);
        assertThat(user.get("auth/me").status()).isEqualTo(401);
        assertThat(refreshWith(refresh).status()).isEqualTo(401);
    }

    @Test
    void providers_are_public_and_report_none_when_unconfigured() {
        var res = newClient().get("auth/providers");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).contains("\"google\":false").contains("\"github\":false");
    }

    @Test
    void oauth_without_a_configured_provider_returns_to_the_login_page() {
        var res = newClient().get("auth/google");
        assertThat(res.status()).isEqualTo(302);
        assertThat(res.header("Location")).endsWith("/login?error=oauth");
    }

    // ── Org roles ────────────────────────────────────────────────────────────

    private record OrgPair(ApiClient admin, ApiClient developer) {
    }

    private OrgPair orgWithDeveloper() {
        var admin = newUser();
        var developer = newUser();

        assertThat(admin.post("orgs/" + admin.orgSlug + "/members/invite", Map.of("email", developer.email)).status())
                .isEqualTo(200);

        var token = mail.inviteToken(developer.email);
        assertThat(developer.get("auth/invite/accept?token=" + token).status()).isEqualTo(302);

        developer.orgSlug = admin.orgSlug;
        return new OrgPair(admin, developer);
    }

    @Test
    void invite_token_is_single_use() {
        var admin = newUser();
        var invitee = newUser();
        admin.post("orgs/" + admin.orgSlug + "/members/invite", Map.of("email", invitee.email));
        var token = mail.inviteToken(invitee.email);

        assertThat(invitee.get("auth/invite/accept?token=" + token).status()).isEqualTo(302);
        assertThat(invitee.get("auth/invite/accept?token=" + token).status()).isEqualTo(400);
    }

    @Test
    void invite_for_someone_else_is_forbidden() {
        var admin = newUser();
        var invitee = newUser();
        var intruder = newUser();
        admin.post("orgs/" + admin.orgSlug + "/members/invite", Map.of("email", invitee.email));

        assertThat(intruder.get("auth/invite/accept?token=" + mail.inviteToken(invitee.email)).status()).isEqualTo(403);
    }

    @Test
    void anonymous_invite_acceptance_goes_to_login() {
        var res = newClient().get("auth/invite/accept?token=abc");
        assertThat(res.status()).isEqualTo(302);
        assertThat(res.header("Location")).endsWith("/login?invite=abc");
    }

    @Test
    void developer_gets_403_on_an_admin_route_and_admin_gets_200_on_a_member_route() {
        var pair = orgWithDeveloper();

        assertThat(pair.developer().post("orgs/" + pair.developer().orgSlug + "/members/invite",
                Map.of("email", "x@example.com")).status()).isEqualTo(403);

        assertThat(pair.admin().get("orgs/" + pair.admin().orgSlug + "/members").status()).isEqualTo(200);
        assertThat(pair.developer().get("orgs/" + pair.developer().orgSlug + "/members").status()).isEqualTo(200);
    }

    @Test
    void non_member_gets_403_on_an_org() {
        var owner = newUser();
        var stranger = newUser();

        assertThat(stranger.get("orgs/" + owner.orgSlug + "/projects").status()).isEqualTo(403);
    }

    @Test
    void demoting_the_last_admin_is_400() {
        var admin = newUser();
        var id = admin.get("orgs/" + admin.orgSlug + "/members").json().get(0).get("id").asString();

        assertThat(admin.patch("orgs/" + admin.orgSlug + "/members/" + id + "/role", Map.of("role", "developer")).status())
                .isEqualTo(400);
        assertThat(admin.delete("orgs/" + admin.orgSlug + "/members/" + id).status()).isEqualTo(400);
    }

    @Test
    void admin_can_be_demoted_once_another_admin_exists() {
        var pair = orgWithDeveloper();
        var members = pair.admin().get("orgs/" + pair.admin().orgSlug + "/members").json();

        assertThat(pair.admin().patch("orgs/" + pair.admin().orgSlug + "/members/" + idOf(members, pair.developer().email)
                + "/role", Map.of("role", "admin")).status()).isEqualTo(200);
        assertThat(pair.admin().patch("orgs/" + pair.admin().orgSlug + "/members/" + idOf(members, pair.admin().email)
                + "/role", Map.of("role", "developer")).status()).isEqualTo(200);
    }

    @Test
    void removed_members_lose_access() {
        var pair = orgWithDeveloper();
        var members = pair.admin().get("orgs/" + pair.admin().orgSlug + "/members").json();

        assertThat(pair.admin().delete("orgs/" + pair.admin().orgSlug + "/members/" + idOf(members, pair.developer().email))
                .status()).isEqualTo(200);
        assertThat(pair.developer().get("orgs/" + pair.developer().orgSlug + "/members").status()).isEqualTo(403);
        assertThat(pair.admin().get("orgs/" + pair.admin().orgSlug + "/members").json().size()).isEqualTo(1);
    }

    private static String idOf(JsonNode members, String email) {
        for (var m : members)
            if (m.get("user").get("email").asString().equals(email)) return m.get("id").asString();
        throw new AssertionError("No member " + email);
    }

    @Test
    void orgs_list_reports_role_and_counts() {
        var pair = orgWithDeveloper();
        var org = pair.developer().get("orgs").json();

        JsonNode shared = null;
        for (var o : org) if (o.get("slug").asString().equals(pair.admin().orgSlug)) shared = o;

        assertThat(shared).isNotNull();
        assertThat(shared.get("role").asString()).isEqualTo("developer");
        assertThat(shared.get("memberCount").asInt()).isEqualTo(2);
        assertThat(shared.get("projectCount").asInt()).isZero();
    }

    // ── Projects and keys ────────────────────────────────────────────────────

    record CreatedProject(String id, String slug, String schema, String anonKey, String serviceKey) {
    }

    static CreatedProject createProject(ApiClient user, String name) {
        var res = user.post("orgs/" + user.orgSlug + "/projects", Map.of("name", name));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        var body = res.json();
        var project = body.get("project");
        var keys = body.get("keys");

        return new CreatedProject(
                project.get("id").asString(),
                project.get("slug").asString(),
                project.get("dbSchema").asString(),
                keys.get("anonKey").asString(),
                keys.get("serviceRoleKey").asString());
    }

    static void createTodos(ApiClient user, CreatedProject p) {
        var res = user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/tables", Map.of(
                "name", "todos",
                "columns", List.of(
                        Map.of("name", "id", "type", "bigint", "isNullable", false, "isPrimaryKey", true),
                        Map.of("name", "title", "type", "text", "isNullable", false, "isPrimaryKey", false))));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
    }

    static ApiClient.Response withKey(ApiClient client, String method, String path, String key, Object body) {
        return client.send(ApiClient.withBody(client.request(path), method, body).header("Authorization", "Bearer " + key));
    }

    @Test
    void creating_a_project_creates_its_schema_and_auth_users() {
        var user = newUser();
        var project = createProject(user, "Demo");

        var tables = user.get("orgs/" + user.orgSlug + "/projects/" + project.slug() + "/tables").json();
        assertThat(tables.toString()).contains("auth_users");
        assertThat(project.schema()).matches("^proj_[0-9a-f]{8}$");
    }

    @Test
    void project_GET_never_returns_the_service_role_key() {
        var user = newUser();
        var project = createProject(user, "Demo");

        var json = user.get("orgs/" + user.orgSlug + "/projects/" + project.slug()).body();
        assertThat(json).doesNotContain(project.serviceKey());
        assertThat(json.toLowerCase()).doesNotContain("servicerole").doesNotContain("secret");
    }

    @Test
    void developer_cannot_create_a_project() {
        var pair = orgWithDeveloper();
        assertThat(pair.developer().post("orgs/" + pair.developer().orgSlug + "/projects", Map.of("name", "Nope")).status())
                .isEqualTo(403);
    }

    @Test
    void unknown_column_type_is_400() {
        var user = newUser();
        var p = createProject(user, "Demo");

        var res = user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/tables", Map.of(
                "name", "things",
                "columns", List.of(Map.of("name", "id", "type", "money", "isNullable", false, "isPrimaryKey", true))));
        assertThat(res.status()).isEqualTo(400);
    }

    @Test
    void key_for_project_A_is_403_against_project_B() {
        var user = newUser();
        var a = createProject(user, "A");
        var b = createProject(user, "B");
        createTodos(user, b);

        assertThat(withKey(newClient(), "GET", "projects/" + b.slug() + "/rest/todos", a.serviceKey(), null).status())
                .isEqualTo(403);
    }

    @Test
    void anon_key_reads_but_gets_403_on_write() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);
        var http = newClient();

        assertThat(withKey(http, "POST", "projects/" + p.slug() + "/rest/todos", p.serviceKey(), Map.of("title", "first")).status())
                .isEqualTo(201);

        var read = withKey(http, "GET", "projects/" + p.slug() + "/rest/todos?id=eq.1", p.anonKey(), null);
        assertThat(read.status()).isEqualTo(200);
        assertThat(read.json().get(0).get("title").asString()).isEqualTo("first");

        assertThat(withKey(http, "POST", "projects/" + p.slug() + "/rest/todos", p.anonKey(), Map.of("title", "nope")).status())
                .isEqualTo(403);
    }

    @Test
    void service_key_updates_and_deletes_by_primary_key() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);
        var http = newClient();
        var rest = "projects/" + p.slug() + "/rest/todos";

        var id = withKey(http, "POST", rest, p.serviceKey(), Map.of("title", "draft")).json().get("id").asLong();

        var updated = withKey(http, "PATCH", rest + "/" + id, p.serviceKey(), Map.of("title", "final"));
        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.json().get("title").asString()).isEqualTo("final");

        assertThat(withKey(http, "DELETE", rest + "/" + id, p.serviceKey(), null).status()).isEqualTo(200);
        assertThat(withKey(http, "DELETE", rest + "/" + id, p.serviceKey(), null).status()).isEqualTo(404);
    }

    @Test
    void filters_order_and_limit_apply() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);
        var http = newClient();
        var rest = "projects/" + p.slug() + "/rest/todos";
        for (var title : List.of("a", "b", "c")) withKey(http, "POST", rest, p.serviceKey(), Map.of("title", title));

        var res = withKey(http, "GET", rest + "?id=gt.1&order=id.desc&limit=1&select=title", p.anonKey(), null).json();
        assertThat(res.size()).isEqualTo(1);
        assertThat(res.get(0).get("title").asString()).isEqualTo("c");
        assertThat(res.get(0).has("id")).isFalse();
    }

    @Test
    void malformed_filter_is_400_not_the_whole_table() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);

        assertThat(withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/todos?id=lte100", p.anonKey(), null).status())
                .isEqualTo(400);
    }

    @Test
    void missing_or_bad_key_is_401() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);

        assertThat(newClient().get("projects/" + p.slug() + "/rest/todos").status()).isEqualTo(401);
        assertThat(withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/todos", "garbage", null).status()).isEqualTo(401);
    }

    @Test
    void rotated_keys_stop_working() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);

        var rotated = user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/keys/rotate", null);
        assertThat(rotated.status()).isEqualTo(200);

        assertThat(withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/todos", p.anonKey(), null).status())
                .isEqualTo(401);
        assertThat(withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/todos",
                rotated.json().get("anonKey").asString(), null).status()).isEqualTo(200);
    }

    // ── SQL editor ───────────────────────────────────────────────────────────

    static ApiClient.Response runSql(ApiClient user, CreatedProject p, String sql) {
        return user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/sql", Map.of("sql", sql));
    }

    @Test
    void sql_editor_runs_one_statement_and_rejects_two() {
        var user = newUser();
        var p = createProject(user, "Demo");

        var ok = runSql(user, p, "select 41 + 1 as answer");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.json().get("rows").get(0).get("answer").asInt()).isEqualTo(42);

        assertThat(runSql(user, p, "select 1; select 2").status()).isEqualTo(400);
        assertThat(runSql(user, p, "select $1, $2; select 3").status()).isEqualTo(400);
    }

    @Test
    void sql_editor_shows_the_database_error_message() {
        var user = newUser();
        var p = createProject(user, "Demo");

        var res = runSql(user, p, "select * from no_such_table");
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body()).contains("no_such_table");
    }

    @Test
    void sql_editor_reports_rows_affected_for_writes() {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);

        var body = runSql(user, p, "insert into todos (title) values ('a'), ('b'), ('c')").json();
        assertThat(body.get("rowCount").asInt()).isEqualTo(3);
        assertThat(body.get("command").asString()).isEqualTo("INSERT");
    }

    @Test
    void sql_editor_records_history() {
        var user = newUser();
        var p = createProject(user, "Demo");
        runSql(user, p, "select 1");

        var history = user.get("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/sql/history").json();
        assertThat(history.get(0).get("sql").asString()).isEqualTo("select 1");
    }

    // The proof that least privilege works: the project role cannot see the control plane.
    @ParameterizedTest
    @ValueSource(strings = {"select * from public.users", "select * from public.projects", "select * from public.refresh_tokens"})
    void sql_editor_cannot_read_control_plane_tables(String sql) {
        var user = newUser();
        var p = createProject(user, "Demo");

        var res = runSql(user, p, sql);
        assertThat(res.status()).isGreaterThanOrEqualTo(400);
        assertThat(res.body()).doesNotContain(user.email);
    }

    // Each project's SQL runs as its own login role. With one shared role, any project could read,
    // write and drop every other project's tables by naming the schema.
    @ParameterizedTest
    @ValueSource(strings = {
        "select * from %s.todos",
        "select * from %s.auth_users",
        "insert into %s.todos (title) values ('pwned')",
        "drop table %s.todos",
        "create table %s.planted (id int)",
        "set role %s"
    })
    void sql_editor_cannot_touch_another_projects_schema(String template) {
        var attacker = newUser();
        var mine = createProject(attacker, "Mine");
        var owner = newUser();
        var victim = createProject(owner, "Victim");
        createTodos(owner, victim);
        assertThat(runSql(owner, victim, "insert into todos (title) values ('secret')").status()).isEqualTo(200);

        var res = runSql(attacker, mine, template.formatted(victim.schema()));
        assertThat(res.status()).as(res.body()).isGreaterThanOrEqualTo(400);

        // And the victim's data is untouched.
        var check = runSql(owner, victim, "select title from todos").json();
        assertThat(check.get("rows").get(0).get("title").asString()).isEqualTo("secret");
    }

    @Test
    void tables_created_in_the_sql_editor_are_reachable_by_the_data_api() {
        var user = newUser();
        var p = createProject(user, "Demo");

        assertThat(runSql(user, p, "create table notes (id int primary key, body text)").status()).isEqualTo(200);
        assertThat(runSql(user, p, "insert into notes values (1, 'hello')").status()).isEqualTo(200);

        var res = withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/notes", p.anonKey(), null);
        assertThat(res.status()).as(res.body()).isEqualTo(200);
        assertThat(res.json().get(0).get("body").asString()).isEqualTo("hello");

        var write = withKey(newClient(), "POST", "projects/" + p.slug() + "/rest/notes", p.serviceKey(),
                Map.of("id", 2, "body", "via api"));
        assertThat(write.status()).as(write.body()).isEqualTo(201);
    }

    // Before per-project roles, SQL-editor tables were owned by the shared tenant role. The first
    // SQL editor run after upgrading hands them to the project role; the data API must still work.
    @Test
    void legacy_tables_owned_by_the_shared_role_are_handed_over() throws Exception {
        var user = newUser();
        var p = createProject(user, "Demo");

        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var setup = admin.createStatement()) {
            setup.execute("""
                    UPDATE projects SET db_role_password = NULL WHERE slug = '%1$s';
                    GRANT CREATE ON SCHEMA %2$s TO supavolt_tenant;
                    SET ROLE supavolt_tenant;
                    CREATE TABLE %2$s.legacy (id int PRIMARY KEY, note text);
                    INSERT INTO %2$s.legacy VALUES (1, 'old');
                    RESET ROLE;
                    """.formatted(p.slug(), p.schema()));
        }

        assertThat(runSql(user, p, "insert into legacy values (2, 'new')").status()).isEqualTo(200);
        assertThat(runSql(user, p, "alter table legacy add column extra text").status()).isEqualTo(200);

        var read = withKey(newClient(), "GET", "projects/" + p.slug() + "/rest/legacy?order=id", p.anonKey(), null);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(read.json().size()).isEqualTo(2);

        // And the shared role lost CREATE on the schema.
        var privilege = runSql(user, p,
                "select has_schema_privilege('supavolt_tenant', '" + p.schema() + "', 'CREATE') as c").json();
        assertThat(privilege.get("rows").get(0).get("c").asBoolean()).isFalse();
    }

    @Test
    void sql_editor_survives_the_role_changing_its_own_password() {
        var user = newUser();
        var p = createProject(user, "Demo");

        assertThat(runSql(user, p, "alter role " + p.schema() + " password 'changed-from-inside'").status()).isEqualTo(200);
        assertThat(runSql(user, p, "select 1").status()).isEqualTo(200);
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    @Test
    void deleting_another_projects_storage_object_is_404() {
        var user = newUser();
        var owner = createProject(user, "Owner");
        var other = createProject(user, "Other");
        var base = "orgs/" + user.orgSlug + "/projects";

        var bucketId = user.post(base + "/" + owner.slug() + "/storage/buckets", Map.of("name", "files", "access", "private"))
                .json().get("id").asString();

        var created = user.post(base + "/" + owner.slug() + "/storage/buckets/" + bucketId + "/objects", Map.of(
                "name", "a.txt", "size", 1, "contentType", "text/plain",
                "objectKey", owner.id() + "/" + bucketId + "/x/a.txt"));
        assertThat(created.status()).as(created.body()).isEqualTo(200);
        var objectId = created.json().get("id").asString();

        assertThat(user.delete(base + "/" + other.slug() + "/storage/objects/" + objectId).status()).isEqualTo(404);
    }

    @Test
    void registering_an_object_key_outside_the_project_prefix_is_403() {
        var user = newUser();
        var mine = createProject(user, "Mine");
        var theirs = createProject(user, "Theirs");
        var base = "orgs/" + user.orgSlug + "/projects/" + mine.slug() + "/storage/buckets";

        var bucketId = user.post(base, Map.of("name", "files", "access", "private")).json().get("id").asString();

        var res = user.post(base + "/" + bucketId + "/objects", Map.of(
                "name", "a.txt", "size", 1, "contentType", "text/plain",
                "objectKey", theirs.id() + "/" + bucketId + "/x/a.txt"));
        assertThat(res.status()).isEqualTo(403);
    }

    @Test
    void upload_urls_are_presigned_under_the_project_prefix() {
        var user = newUser();
        var p = createProject(user, "Demo");
        var base = "orgs/" + user.orgSlug + "/projects/" + p.slug() + "/storage/buckets";
        var bucketId = user.post(base, Map.of("name", "files", "access", "public")).json().get("id").asString();

        var upload = user.post(base + "/" + bucketId + "/upload-url",
                Map.of("fileName", "../../escape.txt", "contentType", "text/plain", "size", 5)).json();

        assertThat(upload.get("objectKey").asString()).startsWith(p.id() + "/" + bucketId + "/").endsWith("/escape.txt");
        assertThat(upload.get("uploadUrl").asString()).startsWith("http://127.0.0.1:9/test/").contains("X-Amz-Signature=");
    }

    // ── Project (end-user) auth ──────────────────────────────────────────────

    @Test
    void end_users_sign_up_and_sign_in_per_project() {
        var user = newUser();
        var p = createProject(user, "Demo");
        var http = newClient();
        var auth = "projects/" + p.slug() + "/auth/";

        var signUp = http.post(auth + "signup", Map.of("email", "end@user.com", "password", "pw-123456"));
        assertThat(signUp.status()).as(signUp.body()).isEqualTo(200);
        assertThat(signUp.json().get("accessToken").asString()).isNotBlank();
        assertThat(signUp.json().get("user").get("provider").asString()).isEqualTo("email");

        assertThat(http.post(auth + "signup", Map.of("email", "END@user.com", "password", "x")).status()).isEqualTo(409);
        assertThat(http.post(auth + "signin", Map.of("email", "end@user.com", "password", "pw-123456")).status()).isEqualTo(200);
        assertThat(http.post(auth + "signin", Map.of("email", "end@user.com", "password", "wrong")).status()).isEqualTo(401);

        var users = user.get("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/auth/users").json();
        assertThat(users.get(0).get("email").asString()).isEqualTo("end@user.com");
    }

    @Test
    void magic_links_work_once() {
        var user = newUser();
        var p = createProject(user, "Demo");
        var http = newClient();
        var auth = "projects/" + p.slug() + "/auth/";

        assertThat(http.post(auth + "magic-link", Map.of("email", "magic@user.com")).status()).isEqualTo(200);
        var token = mail.inviteToken("magic@user.com");

        assertThat(http.get(auth + "magic-link/verify?token=" + token).status()).isEqualTo(200);
        assertThat(http.get(auth + "magic-link/verify?token=" + token).status()).isEqualTo(400);
    }

    @Test
    void oauth_secrets_are_write_only() throws Exception {
        var user = newUser();
        var p = createProject(user, "Demo");
        var settings = "orgs/" + user.orgSlug + "/projects/" + p.slug() + "/auth/settings";

        var saved = user.post(settings, Map.of(
                "siteUrl", "https://app.example.com/",
                "googleClientId", "google-id",
                "googleClientSecret", "super-secret-value",
                "redirectUrls", List.of("https://app.example.com/callback/")));
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);

        var read = user.get(settings);
        assertThat(read.body()).doesNotContain("super-secret-value");
        assertThat(read.json().get("googleConfigured").asBoolean()).isTrue();
        assertThat(read.json().get("githubConfigured").asBoolean()).isFalse();
        assertThat(read.json().get("siteUrl").asString()).isEqualTo("https://app.example.com");
        assertThat(read.json().get("redirectUrls").get(0).asString()).isEqualTo("https://app.example.com/callback");

        // Encrypted at rest, not just hidden.
        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var query = admin.prepareStatement("SELECT google_client_secret, auth_jwt_secret FROM projects WHERE slug = ?")) {
            query.setString(1, p.slug());
            try (var rs = query.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNotBlank().doesNotContain("super-secret-value");
                assertThat(rs.getString(2)).isNotBlank().hasSizeGreaterThan(64);
            }
        }
    }

    // Secrets the .NET API encrypted with ASP.NET Data Protection cannot be decrypted here. A project
    // carrying them must keep working: the role is re-keyed, the end-user signing secret replaced,
    // and OAuth client secrets read as not configured.
    @Test
    void projects_with_secrets_from_the_dotnet_api_keep_working() throws Exception {
        var user = newUser();
        var p = createProject(user, "Legacy");
        var unreadable = "CfDJ8AAAAAAAAAAAAAAAAAAAAAAprotectedByDataProtection";

        try (var admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var update = admin.prepareStatement("UPDATE projects SET auth_jwt_secret = ?, db_role_password = ?,"
                     + " google_client_id = 'gid', google_client_secret = ? WHERE slug = ?")) {
            update.setString(1, unreadable);
            update.setString(2, unreadable);
            update.setString(3, unreadable);
            update.setString(4, p.slug());
            assertThat(update.executeUpdate()).isEqualTo(1);
        }

        assertThat(runSql(user, p, "select 1").status()).isEqualTo(200);

        var http = newClient();
        var auth = "projects/" + p.slug() + "/auth/";
        assertThat(http.post(auth + "signup", Map.of("email", "after@upgrade.com", "password", "pw-123456")).status()).isEqualTo(200);
        assertThat(http.post(auth + "signin", Map.of("email", "after@upgrade.com", "password", "pw-123456")).status()).isEqualTo(200);

        var settings = user.get("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/auth/settings").json();
        assertThat(settings.get("googleClientId").asString()).isEqualTo("gid");
        assertThat(settings.get("googleConfigured").asBoolean()).isFalse();
    }

    @Test
    void health_is_public() {
        var res = newClient().get("health");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("status").asString()).isEqualTo("ok");
    }
}
