package com.potner.sensor.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    long countByDeviceMessageId(String deviceMessageId);

    List<SensorReading> findAllByDeviceMessageIdOrderBySensorType(String deviceMessageId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO sensor_reading (
                plant_id,
                robot_id,
                source_device_id,
                device_message_id,
                sensor_type,
                measured_value,
                unit,
                quality,
                measured_at,
                received_at
            ) VALUES (
                :plantId,
                :robotId,
                :sourceDeviceId,
                :deviceMessageId,
                :sensorType,
                :measuredValue,
                :unit,
                'GOOD',
                :measuredAt,
                :receivedAt
            )
            """, nativeQuery = true)
    int insertReadingIgnoringDuplicate(
            @Param("plantId") String plantId,
            @Param("robotId") String robotId,
            @Param("sourceDeviceId") String sourceDeviceId,
            @Param("deviceMessageId") String deviceMessageId,
            @Param("sensorType") String sensorType,
            @Param("measuredValue") BigDecimal measuredValue,
            @Param("unit") String unit,
            @Param("measuredAt") LocalDateTime measuredAt,
            @Param("receivedAt") LocalDateTime receivedAt
    );
}
