package com.potner.device.domain;

import com.potner.user.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 식물을 담당하는 로봇이다.
 *
 * <p>{@code connection_status}와 {@code last_seen_at} 컬럼은 매핑하지 않는다.
 * Heartbeat는 {@code iot_device}만 갱신하므로 그 컬럼들은 갱신되지 않으며,
 * 로봇의 연결 상태는 하위 장치 상태에서 파생해 계산한다.
 * 같은 사실을 두 곳에 저장하지 않기 위한 선택이다.
 */
@Entity
@Table(name = "robot")
public class Robot {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /**
     * 사용자가 로봇 스티커에서 읽어 입력하는 값이다. MQTT 토픽 세그먼트, 페이로드 deviceId,
     * Mosquitto 계정명과 같아야 하므로 등록 후에는 바꾸지 않는다.
     *
     * <p>유일성은 이 컬럼이 아니라 활성 행만 값을 갖는 {@code active_device_uid} 생성 컬럼에
     * 걸려 있다. 해제된 행은 이력이라 같은 코드가 여러 번 나타날 수 있다.
     */
    @Column(name = "device_uid", length = 100, nullable = false)
    private String deviceUid;

    /**
     * 해제 시각. NULL 이면 활성이다. 물리 삭제 대신 이 값을 채운다 — 측정 이력이 RESTRICT 로
     * 참조하고 있어 지울 수 없고, 지워서도 안 된다(이전 주인의 기록이다).
     */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /**
     * 업로드 토큰의 SHA-256 해시다. 장치는 사용자 JWT 를 가질 수 없어 별도 자격이 필요하다.
     * Refresh Token 과 같은 이유로 원문은 저장하지 않는다. 원문은 발급 응답에서 한 번만 노출된다.
     */
    @Column(name = "upload_token_hash", length = 64)
    private String uploadTokenHash;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /** 로봇 고유 데이터라 하위 장치에서 파생할 수 없다. 젯슨이 보고한다. */
    @Column(name = "battery_percent", columnDefinition = "tinyint unsigned")
    private Integer batteryPercent;

    /** 잔량을 받은 시각. 값만 있으면 사흘 전 값이 현재값처럼 보인다. */
    @Column(name = "battery_measured_at")
    private LocalDateTime batteryMeasuredAt;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    /**
     * 젯슨이 알려 준 현재 행동 상태다.
     *
     * <p>{@code insertable = false} 다. 등록 시점에는 상태를 알 수 없고 DB 기본값 'IDLE' 이
     * 들어가야 한다. 여기서 값을 실어 보내면 새 로봇마다 상태를 정해 주는 코드가 필요해진다.
     * {@code connection_status} 를 아예 매핑하지 않은 것과 같은 방침이다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_state", length = 20, nullable = false, insertable = false)
    private RobotState currentState;

    @Column(name = "state_changed_at", insertable = false)
    private LocalDateTime stateChangedAt;

    protected Robot() {
    }

    /**
     * connection_status 는 매핑하지 않으므로 DB 기본값 'OFFLINE' 이 들어간다.
     * 실제 연결 상태는 하위 장치의 heartbeat 에서 파생한다.
     */
    public static Robot register(AppUser user, String deviceUid, String name) {
        Robot robot = new Robot();
        robot.id = UUID.randomUUID().toString();
        robot.user = user;
        robot.deviceUid = deviceUid;
        robot.name = name;
        return robot;
    }

    /**
     * 로봇을 해제한다. 같은 코드로 다른 계정이 새로 등록할 수 있게 된다. 이미 해제된 행은
     * 처음 해제 시각을 유지한다.
     */
    public void release(LocalDateTime releasedAt) {
        if (this.releasedAt == null) {
            this.releasedAt = releasedAt;
        }
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 재발급하면 이전 토큰은 즉시 무효가 된다. 유출 시 회수 수단이 된다. */
    public void replaceUploadTokenHash(String uploadTokenHash) {
        this.uploadTokenHash = uploadTokenHash;
    }

    /**
     * 상태를 바꾼다. 같은 상태면 아무것도 하지 않고 {@code false} 를 돌려준다.
     *
     * <p>젯슨이 같은 상태를 주기적으로 반복 발행한다. 매번 갱신하면 {@code stateChangedAt} 이
     * 마지막 수신 시각이 되어 "언제부터 이 상태인지"를 잃는다. 급수 작업이 이동 시작 시점을
     * 알아야 하므로 그 값이 필요하다.
     *
     * @return 상태가 실제로 바뀌었으면 {@code true}
     */
    /**
     * 배터리 잔량을 기록한다. 같은 값이 다시 와도 시각을 갱신한다.
     *
     * <p>{@link #changeState} 와 다르다. 상태는 "언제부터 이 상태인지"가 필요해서 값이 바뀔
     * 때만 시각을 옮기지만, 배터리는 "이 값이 얼마나 최근 것인지"가 필요하다. 78% 가 계속
     * 보고되는 동안 시각이 멈춰 있으면 살아 있는 로봇의 값을 오래된 것으로 오해한다.
     */
    public void recordBattery(int batteryPercent, LocalDateTime measuredAt) {
        this.batteryPercent = batteryPercent;
        this.batteryMeasuredAt = measuredAt;
    }

    public boolean changeState(RobotState state, LocalDateTime changedAt) {
        if (currentState == state) {
            return false;
        }
        this.currentState = state;
        this.stateChangedAt = changedAt;
        return true;
    }

    public String getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getDeviceUid() {
        return deviceUid;
    }

    public String getUploadTokenHash() {
        return uploadTokenHash;
    }

    public String getName() {
        return name;
    }

    public Integer getBatteryPercent() {
        return batteryPercent;
    }

    public LocalDateTime getBatteryMeasuredAt() {
        return batteryMeasuredAt;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }

    public RobotState getCurrentState() {
        return currentState;
    }

    public LocalDateTime getStateChangedAt() {
        return stateChangedAt;
    }
}
