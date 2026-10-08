package dev.supavolt.api.features.auth;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.config.SupavoltProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The dashboard's Google and GitHub sign-in. Spring Security runs the redirect, state and PKCE;
 * this turns the external identity into a Supavolt user and session cookies, then sends the
 * browser to the dashboard. No session survives: the cookies are the session.
 */
@Component
public class OAuthLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final AuthService auth;
    private final AuthCookies cookies;
    private final String webUrl;

    public OAuthLoginHandlers(AuthService auth, AuthCookies cookies, SupavoltProperties properties) {
        this.auth = auth;
        this.cookies = cookies;
        this.webUrl = properties.web().url();
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        var user = (OAuth2User) authentication.getPrincipal();
        String email = user.getAttribute("email");
        if (email == null || email.isBlank())
            throw AppException.badRequest("The provider did not return an email address");

        String name = user.getAttribute("name");
        String avatar = user.getAttribute("picture") != null ? user.getAttribute("picture") : user.getAttribute("avatar_url");

        cookies.write(response, auth.signInExternal(email, name, avatar));

        var session = request.getSession(false);
        if (session != null) session.invalidate();

        response.sendRedirect(webUrl + "/dashboard");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.sendRedirect(webUrl + "/login?error=oauth");
    }

    /**
     * GitHub omits the email when the user keeps it private. With the {@code user:email} scope the
     * primary verified address is available from /user/emails, so fetch it from there.
     */
    @Component
    public static class UserService extends DefaultOAuth2UserService {

        private final RestClient github = RestClient.create("https://api.github.com");

        @Override
        public OAuth2User loadUser(OAuth2UserRequest request) {
            var user = super.loadUser(request);

            if (!"github".equals(request.getClientRegistration().getRegistrationId()) || user.getAttribute("email") != null)
                return user;

            List<Map<String, Object>> emails = github.get()
                    .uri("/user/emails")
                    .header("Authorization", "Bearer " + request.getAccessToken().getTokenValue())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });

            var primary = emails == null ? null : emails.stream()
                    .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
                    .map(e -> (String) e.get("email"))
                    .findFirst()
                    .orElse(null);
            if (primary == null) return user;

            var attributes = new HashMap<>(user.getAttributes());
            attributes.put("email", primary);
            return new DefaultOAuth2User(user.getAuthorities(), attributes, "id");
        }
    }
}
