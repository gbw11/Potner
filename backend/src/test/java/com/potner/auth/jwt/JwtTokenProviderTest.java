package com.potner.auth.jwt;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String OTHER_SECRET = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmM=";

    @Test
    void issuesAndParsesAccessToken() {
        JwtTokenProvider provider = provider(SECRET, Clock.systemUTC());

        IssuedToken issuedToken = provider.issueAccessToken("user-id");
        JwtPayload payload = provider.parseAccessToken(issuedToken.value());

        assertThat(payload.userId()).isEqualTo("user-id");
        assertThat(payload.tokenType()).isEqualTo(TokenType.ACCESS);
    }

    @Test
    void rejectsTokenWithInvalidSignature() {
        String token = provider(SECRET, Clock.systemUTC()).issueAccessToken("user-id").value();
        JwtTokenProvider otherProvider = provider(OTHER_SECRET, Clock.systemUTC());

        assertThatThrownBy(() -> otherProvider.parseAccessToken(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_ACCESS_TOKEN));
    }

    @Test
    void rejectsExpiredAccessToken() {
        Clock oldClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        String token = provider(SECRET, oldClock).issueAccessToken("user-id").value();

        assertThatThrownBy(() -> provider(SECRET, Clock.systemUTC()).parseAccessToken(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.EXPIRED_ACCESS_TOKEN));
    }

    @Test
    void rejectsAccessTokenAsRefreshToken() {
        JwtTokenProvider provider = provider(SECRET, Clock.systemUTC());
        String accessToken = provider.issueAccessToken("user-id").value();

        assertThatThrownBy(() -> provider.parseRefreshToken(accessToken))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN));
    }

    @Test
    void rejectsShortSecret() {
        JwtProperties properties = new JwtProperties("c2hvcnQ=", 1_800_000, 1_209_600_000);

        assertThatThrownBy(() -> new JwtTokenProvider(properties, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    private JwtTokenProvider provider(String secret, Clock clock) {
        return new JwtTokenProvider(new JwtProperties(secret, 1_800_000, 1_209_600_000), clock);
    }
}
