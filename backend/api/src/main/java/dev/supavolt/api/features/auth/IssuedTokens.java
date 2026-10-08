package dev.supavolt.api.features.auth;

import java.time.OffsetDateTime;

public record IssuedTokens(String accessToken, String refreshToken, OffsetDateTime refreshExpiresAt) {
}
