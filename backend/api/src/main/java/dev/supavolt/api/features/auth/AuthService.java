package dev.supavolt.api.features.auth;

import dev.supavolt.api.common.AppException;
import dev.supavolt.api.common.Slugs;
import dev.supavolt.api.infrastructure.persistence.OrgMember;
import dev.supavolt.api.infrastructure.persistence.OrgMemberRepository;
import dev.supavolt.api.infrastructure.persistence.Organization;
import dev.supavolt.api.infrastructure.persistence.OrganizationRepository;
import dev.supavolt.api.infrastructure.persistence.User;
import dev.supavolt.api.infrastructure.persistence.UserRepository;
import dev.supavolt.contracts.Contracts.LoginRequest;
import dev.supavolt.contracts.Contracts.OrgRole;
import dev.supavolt.contracts.Contracts.RegisterRequest;
import java.util.Locale;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final OrgMemberRepository members;
    private final TokenService tokens;
    private final PasswordEncoder passwords;

    public AuthService(
            UserRepository users,
            OrganizationRepository organizations,
            OrgMemberRepository members,
            TokenService tokens,
            PasswordEncoder passwords) {
        this.users = users;
        this.organizations = organizations;
        this.members = members;
        this.tokens = tokens;
        this.passwords = passwords;
    }

    public IssuedTokens register(RegisterRequest req) {
        var email = normalize(req.email());

        if (users.existsByEmail(email))
            throw AppException.conflict("Email already in use");

        var user = new User();
        user.setEmail(email);
        user.setName(req.name().trim());
        user.setPasswordHash(passwords.encode(req.password()));
        users.save(user);

        createPersonalOrg(user);
        return tokens.issue(user);
    }

    public IssuedTokens login(LoginRequest req) {
        var user = users.findByEmail(normalize(req.email())).orElse(null);

        if (user == null || user.getPasswordHash() == null)
            throw AppException.unauthorized();

        if (!passwords.matches(req.password(), user.getPasswordHash()))
            throw AppException.unauthorized();

        // Hashes from the .NET API (and any older Argon2 parameters) are replaced on sign-in.
        if (passwords.upgradeEncoding(user.getPasswordHash())) {
            user.setPasswordHash(passwords.encode(req.password()));
            users.save(user);
        }

        return tokens.issue(user);
    }

    /** Called from an OAuth callback once the external identity is established. */
    public IssuedTokens signInExternal(String email, String name, String avatarUrl) {
        var normalized = normalize(email);
        var user = users.findByEmail(normalized).orElse(null);

        if (user == null) {
            user = new User();
            user.setEmail(normalized);
            user.setName(name);
            user.setAvatarUrl(avatarUrl);
            users.save(user);
            createPersonalOrg(user);
        } else if (user.getAvatarUrl() == null && avatarUrl != null) {
            user.setAvatarUrl(avatarUrl);
            users.save(user);
        }

        return tokens.issue(user);
    }

    private void createPersonalOrg(User user) {
        var display = user.getName() == null || user.getName().isBlank()
                ? user.getEmail().split("@")[0]
                : user.getName();

        var org = new Organization();
        org.setName(display + "'s Org");
        org.setSlug(Slugs.unique(display + "-org"));
        organizations.save(org);

        var member = new OrgMember();
        member.setOrgId(org.getId());
        member.setUserId(user.getId());
        member.setRole(OrgRole.ADMIN);
        members.save(member);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
