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
                device_message_id,
                sensor_type,
                measured_value,
                unit,
                quality,
                measured_at,
                received_at
            ) VALUES
                (
                    :plantId,
                    :robotId,
                    :deviceMessageId,
                    'TEMPERATURE',
                    :temperatureC,
                    'CELSIUS',
                    'GOOD',
                    :measuredAt,
                    :receivedAt
                ),
                (
                    :plantId,
                    :robotId,
                    :deviceMessageId,
                    'HUMIDITY',
                    :humidityPct,
                    'PERCENT',
                    'GOOD',
                    :measuredAt,
                    :receivedAt
                ),
                (
                    :plantId,
                    :robotId,
                    :deviceMessageId,
                    'SOIL_MOISTURE',
                    :soilMoisturePct,
                    'PERCENT',
                    'GOOD',
                    :measuredAt,
                    :receivedAt
                ),
                (
                    :plantId,
                    :robotId,
                    :deviceMessageId,
                    'ILLUMINANCE',
                    :illuminanceLux,
                    'LUX',
                    'GOOD',
                    :measuredAt,
                    :receivedAt
                )
            """, nativeQuery = true)
    int insertTelemetryReadingsIgnoringDuplicate(
            @Param("plantId") String plantId,
            @Param("robotId") String robotId,
            @Param("deviceMessageId") String deviceMessageId,
            @Param("temperatureC") BigDecimal temperatureC,
            @Param("humidityPct") BigDecimal humidityPct,
            @Param("soilMoisturePct") BigDecimal soilMoisturePct,
            @Param("illuminanceLux") BigDecimal illuminanceLux,
            @Param("measuredAt") LocalDateTime measuredAt,
            @Param("receivedAt") LocalDateTime receivedAt
    );
}
