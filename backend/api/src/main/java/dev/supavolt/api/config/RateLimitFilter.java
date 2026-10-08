package dev.supavolt.api.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed one-minute windows: dashboard sign-in and registration per client address, and each
 * project's end-user auth per project and address. The original had no limit on either. Runs just
 * after Spring Security, so a 429 still carries CORS headers.
 */
@Component
@Order(-99)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern PROJECT_AUTH = Pattern.compile("^/api/projects/([^/]+)/auth(/.*)?$");

    private final LoadingCache<String, Bucket> auth;
    private final LoadingCache<String, Bucket> projectAuth;

    public RateLimitFilter(SupavoltProperties properties) {
        this.auth = buckets(properties.rateLimits().authPermitsPerMinute());
        this.projectAuth = buckets(properties.rateLimits().projectAuthPermitsPerMinute());
    }

    private static LoadingCache<String, Bucket> buckets(int permitsPerMinute) {
        return Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(2))
                .maximumSize(100_000)
                .build(key -> Bucket.builder()
                        .addLimit(limit -> limit.capacity(permitsPerMinute)
                                .refillIntervally(permitsPerMinute, Duration.ofMinutes(1)))
                        .build());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var bucket = bucketFor(request);

        if (bucket != null && !bucket.tryConsume(1)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many requests\",\"status\":429}");
            return;
        }

        chain.doFilter(request, response);
    }

    private Bucket bucketFor(HttpServletRequest request) {
        var path = request.getRequestURI();
        var address = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();

        if ("POST".equals(request.getMethod()) && (path.equals("/api/auth/register") || path.equals("/api/auth/login")))
            return auth.get(address);

        var match = PROJECT_AUTH.matcher(path);
        if (match.matches() && !"OPTIONS".equals(request.getMethod()))
            return projectAuth.get(match.group(1) + ":" + address);

        return null;
    }
}
