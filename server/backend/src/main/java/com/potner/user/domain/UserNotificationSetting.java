package com.potner.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 사용자 알림 수신 설정이다.
 *
 * <p>행이 없는 사용자는 {@link #defaults(String)}가 만드는 기본값으로 취급한다.
 * 회원가입 시 행을 만들지 않아도 되고 기존 사용자 backfill도 필요하지 않다.
 * 기본값은 테이블 DEFAULT와 같은 값을 쓰지만, 저장 시 항상 명시적으로 값을 넣으므로
 * 테이블 DEFAULT는 수동 INSERT를 위한 문서 역할만 한다.
 */
@Entity
@Table(name = "user_notification_setting")
public class UserNotificationSetting {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String userId;

    @Column(name = "all_enabled", nullable = false)
    private boolean allEnabled;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled;

    @Column(name = "plant_care_enabled", nullable = false)
    private boolean plantCareEnabled;

    @Column(name = "marketing_enabled", nullable = false)
    private boolean marketingEnabled;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected UserNotificationSetting() {
    }

    public static UserNotificationSetting defaults(String userId) {
        UserNotificationSetting setting = new UserNotificationSetting();
        setting.userId = userId;
        setting.allEnabled = true;
        setting.pushEnabled = true;
        setting.plantCareEnabled = true;
        // 회원가입에서 선택 동의 항목이므로 켜진 상태로 시작하지 않는다.
        setting.marketingEnabled = false;
        return setting;
    }

    /** 넘어온 값 중 null이 아닌 항목만 바꾼다. */
    public void apply(
            Boolean allEnabled,
            Boolean pushEnabled,
            Boolean plantCareEnabled,
            Boolean marketingEnabled
    ) {
        if (allEnabled != null) {
            this.allEnabled = allEnabled;
        }
        if (pushEnabled != null) {
            this.pushEnabled = pushEnabled;
        }
        if (plantCareEnabled != null) {
            this.plantCareEnabled = plantCareEnabled;
        }
        if (marketingEnabled != null) {
            this.marketingEnabled = marketingEnabled;
        }
    }

    /**
     * 이 카테고리의 푸시를 보내도 되는지 판정한다.
     *
     * <p>{@code allEnabled}는 마스터 스위치라 세부 설정과 무관하게 전체를 막고,
     * {@code pushEnabled}는 기기 팝업 알림 자체를 막는다. 둘을 통과한 뒤에야 카테고리 토글을 본다.
     * 세 단계를 모두 만족해야 발송한다.
     */
    public boolean allowsPush(NotificationCategory category) {
        if (!allEnabled || !pushEnabled) {
            return false;
        }
        return switch (category) {
            case PLANT_CARE -> plantCareEnabled;
            case MARKETING -> marketingEnabled;
        };
    }

    public String getUserId() {
        return userId;
    }

    public boolean isAllEnabled() {
        return allEnabled;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public boolean isPlantCareEnabled() {
        return plantCareEnabled;
    }

    public boolean isMarketingEnabled() {
        return marketingEnabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
