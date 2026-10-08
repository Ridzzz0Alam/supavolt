package dev.supavolt.api.config;

import dev.supavolt.api.features.auth.DashboardAuthenticationFilter;
import dev.supavolt.api.features.auth.OAuthLoginHandlers;
import dev.supavolt.api.features.auth.ProjectKey;
import dev.supavolt.api.features.auth.ProjectKeyAuthenticationFilter;
import dev.supavolt.api.features.auth.ProjectKeyService;
import dev.supavolt.api.features.auth.TokenService;
import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Two authentication schemes, as two filter chains. The ProjectKey chain covers the SDK surface
 * (/api/projects/** and /realtime); the Dashboard chain covers everything else. Neither keeps a
 * server-side session. Route-level org policies are {@code @PreAuthorize} on the controllers.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String OAUTH_AUTHORIZATION_BASE = "/api/auth/oauth2/authorization";
    private static final String OAUTH_CALLBACK = "/api/auth/*/callback";

    @Bean
    @Order(1)
    SecurityFilterChain projectKeyChain(HttpSecurity http, ProjectKeyService keys) throws Exception {
        http.securityMatcher("/api/projects/**", "/realtime", "/realtime/**");
        stateless(http);

        http.addFilterBefore(new ProjectKeyAuthenticationFilter(keys), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        // End-user sign-up and sign-in: these are the authentication, so anonymous.
                        .requestMatchers("/api/projects/*/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/projects/*/rest/**").hasAuthority(ProjectKey.ANY)
                        .requestMatchers("/api/projects/*/rest/**").hasAuthority(ProjectKey.SERVICE_ROLE)
                        .requestMatchers(HttpMethod.GET, "/api/projects/*/storage/**").hasAuthority(ProjectKey.ANY)
                        .requestMatchers("/api/projects/*/storage/**").hasAuthority(ProjectKey.SERVICE_ROLE)
                        .requestMatchers("/realtime", "/realtime/**").hasAuthority(ProjectKey.ANY)
                        .anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain dashboardChain(
            HttpSecurity http,
            TokenService tokens,
            SupavoltProperties properties,
            ObjectProvider<OAuthLoginHandlers> oauthHandlers,
            ObjectProvider<OAuthLoginHandlers.UserService> oauthUsers) throws Exception {
        stateless(http);

        http.addFilterBefore(new DashboardAuthenticationFilter(tokens), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers("/error", "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/auth/google", "/api/auth/github", "/api/auth/invite/accept",
                                OAUTH_AUTHORIZATION_BASE + "/*", OAUTH_CALLBACK).permitAll()
                        // Only served when springdoc is enabled (the dev profile).
                        .requestMatchers("/api/openapi/**", "/api/docs", "/api/swagger-ui/**").permitAll()
                        .anyRequest().authenticated());

        var registrations = clientRegistrations(properties);
        if (registrations != null) {
            var handlers = oauthHandlers.getObject();
            var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, OAUTH_AUTHORIZATION_BASE);
            resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

            // The authorization request (state, PKCE verifier) rides in a short-lived session for
            // the round-trip only; the success handler invalidates it.
            http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                    .oauth2Login(o -> o
                            .clientRegistrationRepository(registrations)
                            .authorizationEndpoint(e -> e.authorizationRequestResolver(resolver))
                            .redirectionEndpoint(r -> r.baseUri(OAUTH_CALLBACK))
                            .userInfoEndpoint(u -> u.userService(oauthUsers.getObject()))
                            .loginPage(properties.web().url() + "/login")
                            .successHandler(handlers)
                            .failureHandler(handlers));
        }

        return http.build();
    }

    private static void stateless(HttpSecurity http) throws Exception {
        http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                // An API answers 401 and 403 with a status, never a redirect to a login page.
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
    }

    /**
     * Each provider is registered only when its client id is set; null when neither is. Without
     * them, /api/auth/google and /api/auth/github send the browser back to the login page.
     */
    private static ClientRegistrationRepository clientRegistrations(SupavoltProperties properties) {
        var oauth = properties.oauth();
        var list = new ArrayList<ClientRegistration>();
        var redirect = "{baseUrl}/api/auth/{registrationId}/callback";

        if (oauth.google().configured())
            list.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(oauth.google().clientId())
                    .clientSecret(oauth.google().clientSecret())
                    .redirectUri(redirect)
                    .build());

        if (oauth.github().configured())
            list.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
                    .clientId(oauth.github().clientId())
                    .clientSecret(oauth.github().clientSecret())
                    .redirectUri(redirect)
                    .scope("read:user", "user:email")
                    .build());

        return list.isEmpty() ? null : new InMemoryClientRegistrationRepository(list);
    }
}
