package dev.supavolt.api.features.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * The Dashboard scheme: a JWT carried in the HttpOnly {@code access_token} cookie. An invalid or
 * expired cookie leaves the request anonymous rather than failing it, so anonymous endpoints such
 * as refresh, logout and invite acceptance still work; protected ones answer 401.
 */
public class DashboardAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public DashboardAuthenticationFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var cookie = WebUtils.getCookie(request, AuthCookies.ACCESS_TOKEN);

        if (cookie != null && !cookie.getValue().isEmpty()) {
            var user = tokens.readAccessToken(cookie.getValue());
            if (user != null) {
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
                SecurityContextHolder.setContext(context);
            }
        }

        chain.doFilter(request, response);
    }
}
