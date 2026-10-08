package dev.supavolt.api.features.auth;

import java.util.UUID;

/**
 * The principal of the ProjectKey scheme: an anon or service-role key from the Authorization
 * header. Separate from the dashboard scheme so a project key can never act on the dashboard and
 * vice versa.
 */
public record ProjectKey(UUID projectId, int keyVersion, boolean serviceRole) {

    /** Granted to every valid key. */
    public static final String ANY = "project_key";

    /** Granted only to service-role keys: writes and storage uploads. */
    public static final String SERVICE_ROLE = "service_role";
}
