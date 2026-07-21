package com.potner.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long accessTokenExpiresIn
) {
    private static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, String refreshToken, long accessTokenExpiresIn) {
        return new TokenResponse(accessToken, refreshToken, BEARER, accessTokenExpiresIn);
    }
}
