package com.panel.shared.security.jwt;

import java.time.Instant;

public record TokenPair(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt) {
}
