package dev.supavolt.api.features.auth;

import dev.supavolt.api.config.SupavoltProperties;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The dashboard holds its tokens in HttpOnly cookies, never in a header. The names are a contract
 * with the Next.js middleware: do not rename them.
 */
@Component
public class AuthCookies {

    public static final String ACCESS_TOKEN = "access_token";
    public static final String REFRESH_TOKEN = "refresh_token";

    private final SupavoltProperties.Jwt jwt;
    private final boolean secure;

    public AuthCookies(SupavoltProperties properties) {
        this.jwt = properties.jwt();
        this.secure = properties.cookies().secure();
    }

    public void write(HttpServletResponse response, IssuedTokens tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_TOKEN, tokens.accessToken(), jwt.accessLifetime()));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_TOKEN, tokens.refreshToken(), jwt.refreshLifetime()));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_TOKEN, "", Duration.ZERO));
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_TOKEN, "", Duration.ZERO));
    }

    private String cookie(String name, String value, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
