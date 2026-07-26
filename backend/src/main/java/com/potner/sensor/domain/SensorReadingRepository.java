package com.potner.sensor.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    long countByDeviceMessageId(String deviceMessageId);

    List<SensorReading> findAllByDeviceMessageIdOrderBySensorType(String deviceMessageId);

    Optional<SensorReading> findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
            String plantId,
            SensorType sensorType,
            SensorQuality quality
    );

    /**
     * 서비스 타임존 기준 고정 크기 구간으로 측정값을 집계한다.
     *
     * <p>구간 경계는 {@code measured_at}을 1970-01-01 기준 초로 환산한 뒤
     * {@code offsetSeconds}만큼 이동시켜 계산한다. 세션 타임존이나 MySQL 타임존 테이블에
     * 의존하지 않으므로 배포 환경에 따라 결과가 달라지지 않는다.
     */
    @Query(value = """
            SELECT CAST(FLOOR(
                       (TIMESTAMPDIFF(SECOND, '1970-01-01', measured_at) + :offsetSeconds)
                       / :bucketSeconds
                   ) AS SIGNED) AS bucketIndex,
                   AVG(measured_value) AS averageValue,
                   MIN(measured_value) AS minimumValue,
                   MAX(measured_value) AS maximumValue,
                   COUNT(*) AS sampleCount
              FROM sensor_reading
             WHERE plant_id = :plantId
               AND sensor_type = :sensorType
               AND quality = 'GOOD'
               AND measured_at >= :fromInclusive
               AND measured_at < :toExclusive
             GROUP BY bucketIndex
             ORDER BY bucketIndex
            """, nativeQuery = true)
    List<SensorHistoryBucket> aggregateHistory(
            @Param("plantId") String plantId,
            @Param("sensorType") String sensorType,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("bucketSeconds") long bucketSeconds,
            @Param("offsetSeconds") long offsetSeconds
    );

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
