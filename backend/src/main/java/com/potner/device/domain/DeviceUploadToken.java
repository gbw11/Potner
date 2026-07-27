package com.potner.device.domain;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 장치 업로드 토큰을 만들고 해시한다.
 *
 * <p>장치는 사용자 JWT 를 가질 수 없어 별도 자격이 필요하다. Refresh Token 과 같은 이유로
 * 원문을 저장하지 않고 해시만 남긴다. 저장소가 유출돼도 업로드 권한이 넘어가지 않는다.
 *
 * <p>토큰은 만료가 없다. 장치는 스스로 재발급을 요청할 수 없기 때문이다.
 * 대신 사용자가 재발급하면 이전 토큰이 즉시 무효가 되므로 그것이 회수 수단이 된다.
 */
@Component
public class DeviceUploadToken {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    /** URL 안전 문자만 쓴다. 사용자가 라즈베리 설정 파일에 붙여넣기 때문이다. */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
