package com.potner.auth.jwt;

import java.time.Instant;

public record IssuedToken(String value, Instant expiresAt) {
}
