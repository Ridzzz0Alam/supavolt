package dev.supavolt.api.infrastructure.tenancy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.supavolt.api.config.SupavoltProperties;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The three database roles. The admin pool is Spring's primary DataSource (JPA, Flyway, DDL and
 * introspection). The tenant pool is created here and deliberately not a bean, so nothing
 * autowires it by accident. Project connections are opened per request with the project's own
 * login, and the LISTEN connection is direct and never pooled.
 */
@Component
public class TenantConnections implements DisposableBean {

    private final DataSource admin;
    private final HikariDataSource tenant;
    private final String url;
    private final String directUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final String tenantRole;

    public TenantConnections(DataSource admin, DataSourceProperties adminProperties, SupavoltProperties properties) {
        this.admin = admin;
        this.url = adminProperties.determineUrl();
        this.adminUsername = adminProperties.determineUsername();
        this.adminPassword = adminProperties.determinePassword();
        this.tenantRole = properties.database().tenantUsername();
        this.directUrl = properties.database().directUrl().isBlank() ? url : properties.database().directUrl();

        var config = new HikariConfig();
        config.setPoolName("tenant");
        // stringtype=unspecified lets Postgres infer the type of string parameters, so a primary
        // key or filter value arriving as text still compares against bigint, uuid or jsonb.
        config.setJdbcUrl(url);
        config.addDataSourceProperty("stringtype", "unspecified");
        config.setUsername(tenantRole);
        config.setPassword(properties.database().tenantPassword());
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        this.tenant = new HikariDataSource(config);
    }

    /** Pooled control-plane access: DDL and introspection. */
    public JdbcClient admin() {
        return JdbcClient.create(admin);
    }

    /** Plain statements for DDL, which takes no parameters and must not be parsed for placeholders. */
    public JdbcTemplate adminDdl() {
        return new JdbcTemplate(admin);
    }

    public DataSource adminDataSource() {
        return admin;
    }

    /** Least-privilege access for server-built tenant SQL: the data API and table-editor reads. */
    public JdbcClient tenant() {
        return JdbcClient.create(tenant);
    }

    /**
     * A project's own login role: the only connection user-authored SQL runs on. Unlike the
     * shared tenant role it has no grant on any other project's schema. Not pooled: a pool per
     * project would hold connections for every project ever opened, and the role is capped at 10.
     */
    public Connection openProject(String role, String password) throws SQLException {
        var props = new Properties();
        props.setProperty("user", role);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", "supavolt-sql-editor");
        props.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(url, props);
    }

    /** Direct, non-pooled connection for LISTEN. Never returned to a shared pool. */
    public Connection openListen() throws SQLException {
        var props = new Properties();
        props.setProperty("user", adminUsername);
        if (adminPassword != null) props.setProperty("password", adminPassword);
        props.setProperty("ApplicationName", "supavolt-realtime");
        // A dead TCP connection must not park the listener forever.
        props.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(directUrl, props);
    }

    public String tenantRole() {
        return tenantRole;
    }

    @Override
    public void destroy() {
        tenant.close();
    }
}
