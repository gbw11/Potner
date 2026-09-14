package com.potner.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 사용자의 살아 있는 Refresh Token을 모두 폐기한다.
     * 비밀번호 변경과 회원 탈퇴에서 다른 기기의 세션까지 끊기 위해 사용한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken token
               SET token.revokedAt = :revokedAt
             WHERE token.userId = :userId
               AND token.revokedAt IS NULL
            """)
    int revokeAllByUserId(
            @Param("userId") String userId,
            @Param("revokedAt") LocalDateTime revokedAt
    );
}
