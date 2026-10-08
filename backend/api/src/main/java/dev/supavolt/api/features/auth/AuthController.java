package dev.supavolt.api.features.auth;

import dev.supavolt.api.config.SupavoltProperties;
import dev.supavolt.api.features.members.InviteService;
import dev.supavolt.api.infrastructure.persistence.UserRepository;
import dev.supavolt.contracts.Contracts.AuthProvidersResponse;
import dev.supavolt.contracts.Contracts.CurrentUserResponse;
import dev.supavolt.contracts.Contracts.LoginRequest;
import dev.supavolt.contracts.Contracts.MessageResponse;
import dev.supavolt.contracts.Contracts.RegisterRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService auth;
    private final TokenService tokens;
    private final AuthCookies cookies;
    private final InviteService invites;
    private final UserRepository users;
    private final SupavoltProperties properties;

    public AuthController(
            AuthService auth,
            TokenService tokens,
            AuthCookies cookies,
            InviteService invites,
            UserRepository users,
            SupavoltProperties properties) {
        this.auth = auth;
        this.tokens = tokens;
        this.cookies = cookies;
        this.invites = invites;
        this.users = users;
        this.properties = properties;
    }

    @PostMapping("/register")
    public MessageResponse register(@Valid @RequestBody RegisterRequest req, HttpServletResponse res) {
        cookies.write(res, auth.register(req));
        return new MessageResponse("Registered successfully");
    }

    @PostMapping("/login")
    public MessageResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        cookies.write(res, auth.login(req));
        return new MessageResponse("Logged in successfully");
    }

    @PostMapping("/refresh")
    public MessageResponse refresh(
            @CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String presented, HttpServletResponse res) {
        cookies.write(res, tokens.rotate(presented));
        return new MessageResponse("Tokens refreshed");
    }

    @PostMapping("/logout")
    public MessageResponse logout(@AuthenticationPrincipal DashboardUser user, HttpServletResponse res) {
        if (user != null) tokens.revokeAll(user.id());

        cookies.clear(res);
        return new MessageResponse("Logged out successfully");
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@AuthenticationPrincipal DashboardUser user) {
        return users.findById(user.id())
                .map(u -> ResponseEntity.ok(new CurrentUserResponse(u.getId(), u.getEmail(), u.getName(), u.getAvatarUrl())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    // ── OAuth ────────────────────────────────────────────────────────────────
    // Spring Security's oauth2Login owns state and PKCE; these only start the flow. The callback
    // is /api/auth/{provider}/callback, handled by OAuthLoginHandlers.

    /** Lets the login page offer only the providers that would actually work. */
    @GetMapping("/providers")
    public AuthProvidersResponse providers() {
        var oauth = properties.oauth();
        return new AuthProvidersResponse(oauth.google().configured(), oauth.github().configured());
    }

    @GetMapping("/google")
    public ResponseEntity<Void> google() {
        return startOAuth("google", properties.oauth().google());
    }

    @GetMapping("/github")
    public ResponseEntity<Void> github() {
        return startOAuth("github", properties.oauth().github());
    }

    private ResponseEntity<Void> startOAuth(String provider, SupavoltProperties.Provider config) {
        var target = config.configured()
                ? "/api/auth/oauth2/authorization/" + provider
                : properties.web().url() + "/login?error=oauth";
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /**
     * Invite acceptance requires a signed-in session: the original granted membership to anyone
     * who opened the link, including a mail client prefetching it.
     */
    @GetMapping("/invite/accept")
    public ResponseEntity<Void> acceptInvite(@RequestParam String token, @AuthenticationPrincipal DashboardUser user) {
        var web = properties.web().url();

        var target = user == null
                ? web + "/login?invite=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                : web + "/organizations/" + invites.accept(token, user.id()) + "/projects";

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
