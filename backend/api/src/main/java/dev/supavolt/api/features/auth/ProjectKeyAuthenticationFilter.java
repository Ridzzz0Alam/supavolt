package dev.supavolt.api.features.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The ProjectKey scheme: an API key in the Authorization header, used by the SDK and by the data,
 * storage and realtime endpoints. An invalid key leaves the request anonymous; the anonymous
 * project-auth endpoints still work and every other endpoint answers 401.
 */
public class ProjectKeyAuthenticationFilter extends OncePerRequestFilter {

    private final ProjectKeyService keys;

    public ProjectKeyAuthenticationFilter(ProjectKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // Browsers cannot set headers on a WebSocket handshake, so the realtime client passes the
        // key as a query parameter, as SignalR clients do.
        var token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim()
                : request.getParameter("access_token");

        if (token != null && !token.isEmpty()) {
            var key = keys.validate(token);
            if (key != null) {
                var authorities = new ArrayList<GrantedAuthority>();
                authorities.add(new SimpleGrantedAuthority(ProjectKey.ANY));
                if (key.serviceRole()) authorities.add(new SimpleGrantedAuthority(ProjectKey.SERVICE_ROLE));

                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(key, null, authorities));
                SecurityContextHolder.setContext(context);
            }
        }

        chain.doFilter(request, response);
    }
}
