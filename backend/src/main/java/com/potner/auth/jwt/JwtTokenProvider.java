package com.potner.auth.jwt;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = createSigningKey(properties.secret());
    }

    public IssuedToken issueAccessToken(String userId) {
        return issue(userId, TokenType.ACCESS, properties.accessTokenExpiration());
    }

    public IssuedToken issueRefreshToken(String userId) {
        return issue(userId, TokenType.REFRESH, properties.refreshTokenExpiration());
    }

    public JwtPayload parseAccessToken(String token) {
        return parse(token, TokenType.ACCESS);
    }

    public JwtPayload parseRefreshToken(String token) {
        return parse(token, TokenType.REFRESH);
    }

    public long accessTokenExpiresInSeconds() {
        return properties.accessTokenExpiration() / 1000;
    }

    private IssuedToken issue(String userId, TokenType tokenType, long expirationMillis) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    private JwtPayload parse(String token, TokenType expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String tokenTypeClaim = claims.get(TOKEN_TYPE_CLAIM, String.class);
            Date expiration = claims.getExpiration();
            if (userId == null || userId.isBlank() || tokenTypeClaim == null || expiration == null) {
                throw tokenException(expectedType, false);
            }
            TokenType actualType = TokenType.valueOf(tokenTypeClaim);
            if (actualType != expectedType) {
                throw tokenException(expectedType, false);
            }
            return new JwtPayload(userId, actualType, expiration.toInstant());
        } catch (ExpiredJwtException exception) {
            throw tokenException(expectedType, true);
        } catch (IllegalArgumentException | JwtException exception) {
            throw tokenException(expectedType, false);
        }
    }

    private BusinessException tokenException(TokenType expectedType, boolean expired) {
        if (expectedType == TokenType.ACCESS) {
            return new BusinessException(expired ? ErrorCode.EXPIRED_ACCESS_TOKEN : ErrorCode.INVALID_ACCESS_TOKEN);
        }
        return new BusinessException(expired ? ErrorCode.EXPIRED_REFRESH_TOKEN : ErrorCode.INVALID_REFRESH_TOKEN);
    }

    private SecretKey createSigningKey(String encodedSecret) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET environment variable is required");
        }
        try {
            byte[] secretBytes = Decoders.BASE64.decode(encodedSecret);
            if (secretBytes.length < MINIMUM_SECRET_BYTES) {
                throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
            }
            return Keys.hmacShaKeyFor(secretBytes);
        } catch (DecodingException exception) {
            throw new IllegalStateException("JWT_SECRET must be a valid Base64-encoded value", exception);
        }
    }
}
