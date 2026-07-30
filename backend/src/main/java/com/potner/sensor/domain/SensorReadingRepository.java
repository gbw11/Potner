package com.potner.sensor.domain;

import com.potner.light.domain.DailyLightAccumulation;
import org.springframework.data.domain.Pageable;
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

    /**
     * 그날 측정이 있었는지 본다. 0 이면 상태 리포트가 점수를 주지 않는다.
     *
     * <p>품질을 가리지 않는다. SUSPECT 라도 기기가 살아 있었다는 뜻이고, 여기서 알고 싶은 것은
     * "기록이 있었는가" 다.
     */
    long countByPlantIdAndMeasuredAtBetween(
            String plantId,
            LocalDateTime fromInclusive,
            LocalDateTime toInclusive
    );

    Optional<SensorReading> findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
            String plantId,
            SensorType sensorType,
            SensorQuality quality
    );

    /** 이상 판정 표본. 최신순으로 가져오며 신선도 창 밖의 값은 제외한다. */
    List<SensorReading> findByPlantIdAndSensorTypeAndQualityAndMeasuredAtGreaterThanEqualOrderByMeasuredAtDesc(
            String plantId,
            SensorType sensorType,
            SensorQuality quality,
            LocalDateTime measuredAtFrom,
            Pageable pageable
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

    /**
     * 조도 표본을 시간 적분해 누적 광량과 일조 시간을 구한다.
     *
     * <p>표본 사이 간격을 {@code maxGapSeconds}로 제한하는 것이 핵심이다. 장치가 반나절 조용하면
     * 마지막 측정값이 그 시간 내내 유지된 것으로 계산되어 누적값이 크게 부풀려진다.
     * 대신 {@code coveredSeconds}가 작아지므로 데이터 부족을 드러낼 수 있다.
     *
     * <p>마지막 표본은 다음 표본이 없어 구간을 만들 수 없으므로 제외된다.
     * 수집 주기가 짧으면 무시할 수 있는 크기다.
     */
    @Query(value = """
            SELECT COALESCE(SUM(measured_value * gap_seconds) / 3600, 0) AS accumulatedLuxHour,
                   COALESCE(
                       SUM(IF(measured_value >= :lightOnThresholdLux, gap_seconds, 0)) / 3600,
                       0
                   ) AS lightHours,
                   CAST(COALESCE(SUM(gap_seconds), 0) AS SIGNED) AS coveredSeconds,
                   COUNT(*) AS sampleCount
              FROM (
                    SELECT measured_value,
                           LEAST(
                               TIMESTAMPDIFF(
                                   SECOND,
                                   measured_at,
                                   LEAD(measured_at) OVER (ORDER BY measured_at)
                               ),
                               :maxGapSeconds
                           ) AS gap_seconds
                      FROM sensor_reading
                     WHERE plant_id = :plantId
                       AND sensor_type = 'ILLUMINANCE'
                       AND quality = 'GOOD'
                       AND measured_at >= :fromInclusive
                       AND measured_at < :toExclusive
                   ) samples
             WHERE gap_seconds IS NOT NULL
            """, nativeQuery = true)
    DailyLightAccumulation aggregateDailyLight(
            @Param("plantId") String plantId,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("lightOnThresholdLux") BigDecimal lightOnThresholdLux,
            @Param("maxGapSeconds") long maxGapSeconds
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
