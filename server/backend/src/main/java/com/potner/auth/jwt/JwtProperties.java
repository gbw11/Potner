package com.potner.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiration,
        long refreshTokenExpiration
) {
    public JwtProperties {
        if (accessTokenExpiration <= 0) {
            throw new IllegalArgumentException("jwt.access-token-expiration must be positive");
        }
        if (refreshTokenExpiration <= 0) {
            throw new IllegalArgumentException("jwt.refresh-token-expiration must be positive");
        }
    }
}
