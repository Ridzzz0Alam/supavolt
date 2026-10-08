package dev.supavolt.api.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.supavolt.api.infrastructure.mail.EmailSender;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real API over a throwaway Postgres 17. One container and one application context for the
 * whole run: every test registers its own users and orgs, so tests stay independent without
 * resetting the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTest.TestBeans.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("supavolt")
            .withUsername("supavolt_admin")
            .withPassword("supavolt")
            // The same script docker-compose runs on a fresh volume: creates the least-privilege role.
            .withInitScript("01-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected CapturingEmailSender mail;

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** A cookie-carrying client, as a browser would be. */
    protected ApiClient newClient() {
        return new ApiClient(baseUrl());
    }

    /** A registered, signed-in dashboard user with their personal org. */
    protected ApiClient newUser() {
        var client = newClient();
        client.email = "u" + UUID.randomUUID().toString().replace("-", "") + "@example.com";

        var res = client.post("auth/register",
                Map.of("email", client.email, "password", "correct-horse-battery", "name", "Test"));
        assertThat(res.status()).as(res.body()).isEqualTo(200);

        var orgs = client.get("orgs").json();
        assertThat(orgs.size()).isEqualTo(1);
        client.orgSlug = orgs.get(0).get("slug").asString();
        return client;
    }

    public static class CapturingEmailSender implements EmailSender {

        private static final Pattern TOKEN = Pattern.compile("token=([0-9a-f]+)");
        private final Map<String, String> last = new ConcurrentHashMap<>();

        @Override
        public void send(String to, String subject, String html) {
            last.put(to.toLowerCase(), html);
        }

        public String inviteToken(String to) {
            var html = last.get(to.toLowerCase());
            var match = html == null ? null : TOKEN.matcher(html);
            if (match == null || !match.find()) throw new IllegalStateException("No invite token mailed to " + to);
            return match.group(1);
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        CapturingEmailSender capturingEmailSender() {
            return new CapturingEmailSender();
        }
    }
}
