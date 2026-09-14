package com.potner.sensor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "sensor_reading",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sensor_reading_device_message",
                columnNames = "device_message_id"
        )
)
public class SensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reading_id", nullable = false, columnDefinition = "bigint unsigned")
    private Long id;

    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @Column(name = "source_device_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String sourceDeviceId;

    @Column(name = "device_message_id", length = 100, nullable = false)
    private String deviceMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sensor_type", length = 30, nullable = false)
    private SensorType sensorType;

    @Column(name = "measured_value", precision = 14, scale = 4, nullable = false)
    private BigDecimal measuredValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", length = 20, nullable = false)
    private SensorUnit unit;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality", length = 20, nullable = false)
    private SensorQuality quality;

    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected SensorReading() {
    }

    private SensorReading(
            String plantId,
            String robotId,
            String sourceDeviceId,
            String deviceMessageId,
            SensorType sensorType,
            BigDecimal measuredValue,
            SensorUnit unit,
            SensorQuality quality,
            LocalDateTime measuredAt,
            LocalDateTime receivedAt
    ) {
        this.plantId = plantId;
        this.robotId = robotId;
        this.sourceDeviceId = sourceDeviceId;
        this.deviceMessageId = deviceMessageId;
        this.sensorType = sensorType;
        this.measuredValue = measuredValue;
        this.unit = unit;
        this.quality = quality;
        this.measuredAt = measuredAt;
        this.receivedAt = receivedAt;
    }

    public static SensorReading create(
            String plantId,
            String robotId,
            String sourceDeviceId,
            String deviceMessageId,
            SensorType sensorType,
            BigDecimal measuredValue,
            SensorUnit unit,
            LocalDateTime measuredAt,
            LocalDateTime receivedAt
    ) {
        return new SensorReading(
                plantId,
                robotId,
                sourceDeviceId,
                deviceMessageId,
                sensorType,
                measuredValue,
                unit,
                SensorQuality.GOOD,
                measuredAt,
                receivedAt
        );
    }

    public Long getId() {
        return id;
    }

    public String getPlantId() {
        return plantId;
    }

    public String getRobotId() {
        return robotId;
    }

    public String getSourceDeviceId() {
        return sourceDeviceId;
    }

    public String getDeviceMessageId() {
        return deviceMessageId;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public BigDecimal getMeasuredValue() {
        return measuredValue;
    }

    public SensorUnit getUnit() {
        return unit;
    }

    public SensorQuality getQuality() {
        return quality;
    }

    public LocalDateTime getMeasuredAt() {
        return measuredAt;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
