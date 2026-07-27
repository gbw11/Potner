package com.potner.device.domain;

import com.potner.user.domain.AppUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
     */
    @Column(name = "device_uid", length = 100, nullable = false, unique = true)
    private String deviceUid;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /** 로봇 고유 데이터라 하위 장치에서 파생할 수 없다. 수집 연동 전까지는 비어 있다. */
    @Column(name = "battery_percent", columnDefinition = "tinyint unsigned")
    private Integer batteryPercent;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

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

    public void rename(String name) {
        this.name = name;
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

    public String getName() {
        return name;
    }

    public Integer getBatteryPercent() {
        return batteryPercent;
    }

    public String getFirmwareVersion() {
        return firmwareVersion;
    }
}
