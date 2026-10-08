package dev.supavolt.api.features.projectauth;

import dev.supavolt.contracts.Contracts.ExchangeCodeRequest;
import dev.supavolt.contracts.Contracts.MagicLinkRequest;
import dev.supavolt.contracts.Contracts.MessageResponse;
import dev.supavolt.contracts.Contracts.ProjectAuthResponse;
import dev.supavolt.contracts.Contracts.SignInRequest;
import dev.supavolt.contracts.Contracts.SignUpRequest;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The SDK-facing endpoints: these are the authentication, so they are anonymous (and rate limited). */
@RestController
@RequestMapping("/projects/{projectSlug}/auth")
@Tag(name = "Project auth")
public class ProjectAuthController {

    private final ProjectAuthService auth;

    public ProjectAuthController(ProjectAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public ProjectAuthResponse signUp(@PathVariable String projectSlug, @Valid @RequestBody SignUpRequest req) {
        return auth.signUp(projectSlug, req);
    }

    @PostMapping("/signin")
    public ProjectAuthResponse signIn(@PathVariable String projectSlug, @Valid @RequestBody SignInRequest req) {
        return auth.signIn(projectSlug, req);
    }

    @PostMapping("/magic-link")
    public MessageResponse magicLink(@PathVariable String projectSlug, @Valid @RequestBody MagicLinkRequest req) {
        auth.sendMagicLink(projectSlug, req.email());
        // Always the same response, so the endpoint cannot be used to enumerate accounts.
        return new MessageResponse("If that email exists, a link is on its way");
    }

    @GetMapping("/magic-link/verify")
    public ProjectAuthResponse verifyMagicLink(@PathVariable String projectSlug, @RequestParam String token) {
        return auth.verifyMagicLink(projectSlug, token);
    }

    @PostMapping("/exchange")
    public ProjectAuthResponse exchange(@PathVariable String projectSlug, @Valid @RequestBody ExchangeCodeRequest req) {
        return auth.exchangeCode(projectSlug, req.code());
    }
}
