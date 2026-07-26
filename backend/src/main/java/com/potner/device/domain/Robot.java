package com.potner.device.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    /** 로봇 고유 데이터라 하위 장치에서 파생할 수 없다. 수집 연동 전까지는 비어 있다. */
    @Column(name = "battery_percent", columnDefinition = "tinyint unsigned")
    private Integer batteryPercent;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    protected Robot() {
    }

    public String getId() {
        return id;
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
