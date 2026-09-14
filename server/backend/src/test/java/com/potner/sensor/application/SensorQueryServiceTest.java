package com.potner.sensor.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
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
import com.potner.sensor.domain.SensorUnit;
import com.potner.sensor.dto.CurrentSensorItem;
import com.potner.sensor.dto.CurrentSensorResponse;
import com.potner.sensor.dto.SensorHistoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorQueryServiceTest {

    private static final LocalDateTime SQL_EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final Instant NOW = Instant.parse("2026-07-26T00:40:00Z");

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    private SensorQueryService sensorQueryService;

    @BeforeEach
    void setUp() {
        sensorQueryService = new SensorQueryService(
                plantRepository,
                profileRepository,
                sensorReadingRepository,
                new SensorQueryProperties("+09:00", 15, 14, 365),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void currentSensorsRejectPlantThatIsNotOwnedOrIsDeleted() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_NOT_FOUND);
    }

    @Test
    void missingGrowthProfileIsRejected() {
        givenOwnedPlant();
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND);
    }

    @Test
    void everySensorTypeIsReturnedWithUnitEvenWhenNothingWasCollected() {
        givenOwnedPlant();
        givenGrowthProfile();

        CurrentSensorResponse response = sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID);

        assertThat(response.plantId()).isEqualTo(PLANT_ID);
        assertThat(response.sensors())
                .extracting(
                        CurrentSensorItem::sensorType,
                        CurrentSensorItem::unit,
                        CurrentSensorItem::status,
                        CurrentSensorItem::value
                )
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple(
                                SensorType.TEMPERATURE, SensorUnit.CELSIUS, SensorStatus.NO_DATA, null),
                        org.assertj.core.api.Assertions.tuple(
                                SensorType.HUMIDITY, SensorUnit.PERCENT, SensorStatus.NO_DATA, null),
                        org.assertj.core.api.Assertions.tuple(
                                SensorType.SOIL_MOISTURE, SensorUnit.PERCENT, SensorStatus.NO_DATA, null),
                        org.assertj.core.api.Assertions.tuple(
                                SensorType.ILLUMINANCE, SensorUnit.LUX, SensorStatus.NO_DATA, null)
                );
    }

    @Test
    void freshReadingIsJudgedAgainstAppliedProfile() {
        givenOwnedPlant();
        givenGrowthProfile();
        givenOnlyReading(
                SensorType.SOIL_MOISTURE,
                "32.50",
                SensorUnit.PERCENT,
                LocalDateTime.of(2026, 7, 26, 0, 35)
        );

        CurrentSensorItem soilMoisture = findItem(
                sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID),
                SensorType.SOIL_MOISTURE
        );

        assertThat(soilMoisture.status()).isEqualTo(SensorStatus.LOW);
        assertThat(soilMoisture.value()).isEqualByComparingTo("32.50");
        assertThat(soilMoisture.thresholdMin()).isEqualByComparingTo("40.00");
        assertThat(soilMoisture.thresholdMax()).isEqualByComparingTo("55.00");
    }

    @Test
    void readingOlderThanFreshnessThresholdIsStaleInsteadOfJudged() {
        givenOwnedPlant();
        givenGrowthProfile();
        givenOnlyReading(
                SensorType.SOIL_MOISTURE,
                "32.50",
                SensorUnit.PERCENT,
                LocalDateTime.of(2026, 7, 26, 0, 20)
        );

        CurrentSensorItem soilMoisture = findItem(
                sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID),
                SensorType.SOIL_MOISTURE
        );

        assertThat(soilMoisture.status()).isEqualTo(SensorStatus.STALE);
        assertThat(soilMoisture.value()).isEqualByComparingTo("32.50");
    }

    @Test
    void freshIlluminanceReadingIsReportedButNotJudged() {
        givenOwnedPlant();
        givenGrowthProfile();
        givenOnlyReading(
                SensorType.ILLUMINANCE,
                "8200",
                SensorUnit.LUX,
                LocalDateTime.of(2026, 7, 26, 0, 35)
        );

        CurrentSensorItem illuminance = findItem(
                sensorQueryService.getCurrentSensors(USER_ID, PLANT_ID),
                SensorType.ILLUMINANCE
        );

        assertThat(illuminance.status()).isEqualTo(SensorStatus.NOT_APPLICABLE);
        assertThat(illuminance.value()).isEqualByComparingTo("8200");
        assertThat(illuminance.thresholdMin()).isNull();
        assertThat(illuminance.thresholdMax()).isNull();
    }

    @Test
    void historyRejectsReversedOrEmptyRange() {
        givenOwnedPlant();
        OffsetDateTime instant = OffsetDateTime.parse("2026-07-26T00:00:00+09:00");

        assertThatThrownBy(() -> sensorQueryService.getHistory(
                USER_ID,
                PLANT_ID,
                SensorType.TEMPERATURE,
                instant,
                instant,
                SensorHistoryInterval.HOUR))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_SENSOR_QUERY_RANGE);
    }

    @Test
    void historyRejectsRangeLongerThanIntervalLimit() {
        givenOwnedPlant();

        assertThatThrownBy(() -> sensorQueryService.getHistory(
                USER_ID,
                PLANT_ID,
                SensorType.TEMPERATURE,
                OffsetDateTime.parse("2026-07-01T00:00:00+09:00"),
                OffsetDateTime.parse("2026-07-15T00:00:01+09:00"),
                SensorHistoryInterval.HOUR))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_SENSOR_QUERY_RANGE);
    }

    @Test
    void dayBucketStartIsConvertedFromServiceTimeZoneBackToUtc() {
        givenOwnedPlant();
        long bucketIndex = Duration.between(SQL_EPOCH, LocalDateTime.of(2026, 7, 26, 0, 0)).toSeconds()
                / SensorHistoryInterval.DAY.bucketSeconds();
        // 스텁 안에서 다시 스텁을 만들지 않도록 집계 결과를 먼저 준비한다.
        SensorHistoryBucket koreanDayBucket = bucket(bucketIndex, "24.00", "22.00", "26.00", 2L);
        when(sensorReadingRepository.aggregateHistory(
                PLANT_ID,
                SensorType.TEMPERATURE.name(),
                LocalDateTime.of(2026, 7, 24, 15, 0),
                LocalDateTime.of(2026, 7, 26, 15, 0),
                86400L,
                32400L))
                .thenReturn(List.of(koreanDayBucket));

        SensorHistoryResponse response = sensorQueryService.getHistory(
                USER_ID,
                PLANT_ID,
                SensorType.TEMPERATURE,
                OffsetDateTime.parse("2026-07-25T00:00:00+09:00"),
                OffsetDateTime.parse("2026-07-27T00:00:00+09:00"),
                SensorHistoryInterval.DAY
        );

        assertThat(response.unit()).isEqualTo(SensorUnit.CELSIUS);
        assertThat(response.from()).isEqualTo(LocalDateTime.of(2026, 7, 24, 15, 0));
        assertThat(response.to()).isEqualTo(LocalDateTime.of(2026, 7, 26, 15, 0));
        assertThat(response.points()).hasSize(1);
        // 한국 시간 2026-07-26 00:00 은 UTC 2026-07-25 15:00 이다.
        assertThat(response.points().getFirst().bucketAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 25, 15, 0));
        assertThat(response.points().getFirst().averageValue()).isEqualByComparingTo("24.00");
        assertThat(response.points().getFirst().sampleCount()).isEqualTo(2L);
    }

    private void givenOwnedPlant() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private void givenGrowthProfile() {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getSoilMoistureMinPct()).thenReturn(new BigDecimal("40.00"));
        when(profile.getSoilMoistureMaxPct()).thenReturn(new BigDecimal("55.00"));
        when(profile.getTemperatureMinC()).thenReturn(new BigDecimal("21.00"));
        when(profile.getTemperatureMaxC()).thenReturn(new BigDecimal("29.00"));
        when(profile.getHumidityMinPct()).thenReturn(new BigDecimal("60.00"));
        when(profile.getHumidityMaxPct()).thenReturn(new BigDecimal("80.00"));
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    /**
     * 최신값 조회는 센서 4종에 대해 모두 호출되므로 요청 종류를 구분하는 스텁 하나로 응답한다.
     * 지정한 종류만 측정값이 있고 나머지는 수집되지 않은 상태가 된다.
     */
    private void givenOnlyReading(
            SensorType sensorType,
            String value,
            SensorUnit unit,
            LocalDateTime measuredAt
    ) {
        SensorReading reading = SensorReading.create(
                PLANT_ID,
                "30000000-0000-0000-0000-0000000000cc",
                "40000000-0000-0000-0000-0000000000dd",
                "message-1",
                sensorType,
                new BigDecimal(value),
                unit,
                measuredAt,
                measuredAt
        );
        when(sensorReadingRepository.findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                eq(PLANT_ID),
                any(SensorType.class),
                eq(SensorQuality.GOOD)))
                .thenAnswer(invocation -> invocation.getArgument(1) == sensorType
                        ? Optional.of(reading)
                        : Optional.empty());
    }

    private CurrentSensorItem findItem(CurrentSensorResponse response, SensorType sensorType) {
        return response.sensors().stream()
                .filter(item -> item.sensorType() == sensorType)
                .findFirst()
                .orElseThrow();
    }

    private SensorHistoryBucket bucket(
            long bucketIndex,
            String average,
            String minimum,
            String maximum,
            long sampleCount
    ) {
        SensorHistoryBucket bucket = mock(SensorHistoryBucket.class);
        when(bucket.getBucketIndex()).thenReturn(bucketIndex);
        when(bucket.getAverageValue()).thenReturn(new BigDecimal(average));
        when(bucket.getMinimumValue()).thenReturn(new BigDecimal(minimum));
        when(bucket.getMaximumValue()).thenReturn(new BigDecimal(maximum));
        when(bucket.getSampleCount()).thenReturn(sampleCount);
        return bucket;
    }
}
