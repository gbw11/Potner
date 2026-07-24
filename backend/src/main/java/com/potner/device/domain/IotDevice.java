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

    @Column(name = "device_uid", length = 100, nullable = false, unique = true)
    private String deviceUid;

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
