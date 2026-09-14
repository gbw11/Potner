package com.potner.push.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 기기 하나의 FCM 등록 토큰이다.
 *
 * <p>식별자가 Firebase 설치 ID다. FCM 토큰은 회전하지만 설치 ID는 앱 설치당 안정적이므로,
 * 토큰을 키로 두면 회전할 때마다 옛 행이 고아로 남는다. 설치 ID가 유일하기 때문에 같은 기기가
 * 두 사용자에게 동시에 등록될 수도 없다. 자세한 배경은 V12 마이그레이션 주석에 있다.
 *
 * <p>쓰기는 {@link FcmTokenRepository#upsert} 한 문장으로만 한다. 이 엔티티는 조회 전용이라
 * 상태를 바꾸는 메서드를 두지 않는다.
 */
@Entity
@Table(name = "fcm_token")
public class FcmToken {

    @Id
    @Column(name = "installation_id", length = 128, nullable = false)
    private String installationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "token", length = 512, nullable = false)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", length = 20, nullable = false)
    private PushPlatform platform;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected FcmToken() {
    }

    public String getInstallationId() {
        return installationId;
    }

    public String getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public PushPlatform getPlatform() {
        return platform;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
