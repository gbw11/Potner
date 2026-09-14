package com.potner.sensor.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorHistoryBucket;
import com.potner.sensor.domain.SensorHistoryInterval;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import com.potner.sensor.dto.CurrentSensorItem;
import com.potner.sensor.dto.CurrentSensorResponse;
import com.potner.sensor.dto.SensorHistoryPoint;
import com.potner.sensor.dto.SensorHistoryResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class SensorQueryService {

    /** 집계 쿼리가 구간 번호를 계산할 때 사용하는 기준 시각과 같아야 한다. */
    private static final LocalDateTime SQL_EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final SensorQueryProperties properties;
    private final Clock clock;

    public SensorQueryService(
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            SensorReadingRepository sensorReadingRepository,
            SensorQueryProperties properties,
            Clock clock
    ) {
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public CurrentSensorResponse getCurrentSensors(String userId, String plantId) {
        requireOwnedPlant(userId, plantId);
        return currentSensorsForVerifiedPlant(plantId);
    }

    /**
     * 소유권 검사를 하지 않는다. 호출자가 plantId 의 귀속을 이미 증명한 경우에만 쓴다
     * (예: 장치 토큰 → 로봇 → 활성 배정). 사용자 요청 경로에서 직접 부르면 안 되므로
     * 패키지 밖에 열지 않는다 — presentation 계층에서는 컴파일이 안 된다.
     */
    CurrentSensorResponse currentSensorsForVerifiedPlant(String plantId) {
        GrowthProfileValues profile = findAppliedProfile(plantId);
        LocalDateTime staleBefore = nowUtc().minusMinutes(properties.freshnessThresholdMinutes());

        List<CurrentSensorItem> sensors = Arrays.stream(SensorType.values())
                .map(sensorType -> toCurrentItem(plantId, sensorType, profile, staleBefore))
                .toList();
        return new CurrentSensorResponse(plantId, sensors);
    }

    public SensorHistoryResponse getHistory(
            String userId,
            String plantId,
            SensorType sensorType,
            OffsetDateTime from,
            OffsetDateTime to,
            SensorHistoryInterval interval
    ) {
        requireOwnedPlant(userId, plantId);
        LocalDateTime fromInclusive = toUtc(from);
        LocalDateTime toExclusive = toUtc(to);
        validateRange(fromInclusive, toExclusive, interval);

        List<SensorHistoryPoint> points = sensorReadingRepository.aggregateHistory(
                        plantId,
                        sensorType.name(),
                        fromInclusive,
                        toExclusive,
                        interval.bucketSeconds(),
                        properties.zoneOffsetSeconds())
                .stream()
                .map(bucket -> toPoint(bucket, interval))
                .toList();
        return new SensorHistoryResponse(
                plantId,
                sensorType,
                sensorType.unit(),
                interval,
                fromInclusive,
                toExclusive,
                points
        );
    }

    private CurrentSensorItem toCurrentItem(
            String plantId,
            SensorType sensorType,
            GrowthProfileValues profile,
            LocalDateTime staleBefore
    ) {
        SensorThresholds thresholds = SensorThresholds.of(sensorType, profile);
        Optional<SensorReading> latest = sensorReadingRepository
                .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                        plantId,
                        sensorType,
                        SensorQuality.GOOD
                );
        if (latest.isEmpty()) {
            return new CurrentSensorItem(
                    sensorType,
                    sensorType.unit(),
                    null,
                    null,
                    SensorStatus.NO_DATA,
                    thresholds.min(),
                    thresholds.max()
            );
        }

        SensorReading reading = latest.get();
        // 오래된 측정값을 판정하면 장치가 꺼진 동안 옛 값으로 정상/이상을 단정하게 된다.
        SensorStatus status = reading.getMeasuredAt().isBefore(staleBefore)
                ? SensorStatus.STALE
                : thresholds.evaluate(reading.getMeasuredValue());
        return new CurrentSensorItem(
                sensorType,
                reading.getUnit(),
                reading.getMeasuredValue(),
                reading.getMeasuredAt(),
                status,
                thresholds.min(),
                thresholds.max()
        );
    }

    private SensorHistoryPoint toPoint(SensorHistoryBucket bucket, SensorHistoryInterval interval) {
        long bucketStartInZone = bucket.getBucketIndex() * interval.bucketSeconds();
        LocalDateTime bucketAt = SQL_EPOCH.plusSeconds(bucketStartInZone - properties.zoneOffsetSeconds());
        return new SensorHistoryPoint(
                bucketAt,
                bucket.getAverageValue(),
                bucket.getMinimumValue(),
                bucket.getMaximumValue(),
                bucket.getSampleCount()
        );
    }

    private void validateRange(
            LocalDateTime fromInclusive,
            LocalDateTime toExclusive,
            SensorHistoryInterval interval
    ) {
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new BusinessException(ErrorCode.INVALID_SENSOR_QUERY_RANGE);
        }
        int maxDays = switch (interval) {
            case HOUR -> properties.maxHourIntervalDays();
            case DAY -> properties.maxDayIntervalDays();
        };
        if (Duration.between(fromInclusive, toExclusive).compareTo(Duration.ofDays(maxDays)) > 0) {
            throw new BusinessException(ErrorCode.INVALID_SENSOR_QUERY_RANGE);
        }
    }

    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private GrowthProfileValues findAppliedProfile(String plantId) {
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND));
        return GrowthProfileValues.from(profile);
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private LocalDateTime toUtc(OffsetDateTime value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }
}
