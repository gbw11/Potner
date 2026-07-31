package com.potner.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "iot_device")
public class IotDevice {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "device_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "robot_id", nullable = false)
    private Robot robot;

    /** 유일성은 활성 행만 값을 갖는 {@code active_device_uid} 생성 컬럼에 걸려 있다. */
    @Column(name = "device_uid", length = 100, nullable = false)
    private String deviceUid;

    /** 해제 시각. 로봇 해제와 함께 채워진다. NULL 이면 활성이다. */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 30, nullable = false)
    private IotDeviceType deviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", length = 20, nullable = false)
    private IotDeviceConnectionStatus connectionStatus;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected IotDevice() {
    }

    /**
     * 등록 직후에는 OFFLINE 이다. 장치가 heartbeat 를 보내기 시작하면 ONLINE 으로 바뀐다.
     * {@code deviceUid} 는 MQTT 토픽 세그먼트와 페이로드 deviceId 와 같아야 한다.
     */
    public static IotDevice register(Robot robot, String deviceUid, IotDeviceType deviceType) {
        IotDevice device = new IotDevice();
        device.id = UUID.randomUUID().toString();
        device.robot = robot;
        device.deviceUid = deviceUid;
        device.deviceType = deviceType;
        device.connectionStatus = IotDeviceConnectionStatus.OFFLINE;
        return device;
    }

    /** 로봇 해제와 함께 불린다. 이미 해제된 행은 처음 해제 시각을 유지한다. */
    public void release(LocalDateTime releasedAt) {
        if (this.releasedAt == null) {
            this.releasedAt = releasedAt;
        }
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void recordHeartbeat(LocalDateTime receivedAt) {
        this.connectionStatus = IotDeviceConnectionStatus.ONLINE;
        this.lastSeenAt = receivedAt;
    }

    public String getId() {
        return id;
    }

    public Robot getRobot() {
        return robot;
    }

    public String getDeviceUid() {
        return deviceUid;
    }

    public IotDeviceType getDeviceType() {
        return deviceType;
    }

    public IotDeviceConnectionStatus getConnectionStatus() {
        return connectionStatus;
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
