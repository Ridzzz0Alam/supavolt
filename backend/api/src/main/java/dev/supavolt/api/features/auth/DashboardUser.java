package dev.supavolt.api.features.auth;

import java.util.UUID;

/** The principal of the Dashboard scheme: a signed-in platform user, from the access-token cookie. */
public record DashboardUser(UUID id, String email, String name) {
}
