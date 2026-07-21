package com.potner.auth.jwt;

import java.time.Instant;

public record JwtPayload(String userId, TokenType tokenType, Instant expiresAt) {
}
