package com.potner.sensor.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    boolean existsByDeviceMessageId(String deviceMessageId);

    long countByDeviceMessageId(String deviceMessageId);

    List<SensorReading> findAllByDeviceMessageIdOrderBySensorType(String deviceMessageId);

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO sensor_reading (
                plant_id,
                robot_id,
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
                :deviceMessageId,
                :sensorType,
                :measuredValue,
                :unit,
                :quality,
                :measuredAt,
                :receivedAt
            )
            """, nativeQuery = true)
    int insertIgnoringDuplicate(
            @Param("plantId") String plantId,
            @Param("robotId") String robotId,
            @Param("deviceMessageId") String deviceMessageId,
            @Param("sensorType") String sensorType,
            @Param("measuredValue") BigDecimal measuredValue,
            @Param("unit") String unit,
            @Param("quality") String quality,
            @Param("measuredAt") LocalDateTime measuredAt,
            @Param("receivedAt") LocalDateTime receivedAt
    );
}
