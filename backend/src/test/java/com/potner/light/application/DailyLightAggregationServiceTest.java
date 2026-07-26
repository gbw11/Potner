package com.potner.light.application;

import com.potner.alert.application.AlertEvaluationResult;
import com.potner.alert.application.AlertEvaluationService;
import com.potner.alert.domain.AlertMetricType;
import com.potner.light.config.DailyLightProperties;
import com.potner.light.domain.DailyLightAccumulation;
import com.potner.light.domain.DailyLightStatus;
import com.potner.light.domain.PlantDailyLight;
import com.potner.light.domain.PlantDailyLightRepository;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyLightAggregationServiceTest {

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final LocalDate LIGHT_DATE = LocalDate.of(2026, 7, 26);
    private static final Instant NOW = Instant.parse("2026-07-26T17:00:00Z");
    /** 바질 발아기 기준. 목표 150,000 lux·h, 허용 105,000 ~ 195,000, 목표 일조 15시간. */
    private static final BigDecimal TARGET_LUX_HOUR = new BigDecimal("150000.00");
    private static final BigDecimal MIN_LUX_HOUR = new BigDecimal("105000.00");
    private static final BigDecimal MAX_LUX_HOUR = new BigDecimal("195000.00");
    private static final BigDecimal PHOTOPERIOD = new BigDecimal("15.00");
    private static final long FULL_DAY_SECONDS = 86400L;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private PlantDailyLightRepository dailyLightRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private AlertEvaluationService alertEvaluationService;

    private DailyLightAggregationService aggregationService;

    @BeforeEach
    void setUp() {
        aggregationService = new DailyLightAggregationService(
                plantRepository,
                profileRepository,
                dailyLightRepository,
                sensorReadingRepository,
                alertEvaluationService,
                new DailyLightProperties(
                        new BigDecimal("500"),
                        600L,
                        new BigDecimal("80"),
                        new BigDecimal("0.2"),
                        90
                ),
                new SensorQueryProperties("+09:00", 15, 14, 365),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void serviceTimeZoneDayStartsNineHoursBeforeUtcMidnight() {
        assertThat(aggregationService.startOfDayUtc(LIGHT_DATE))
                .isEqualTo(LocalDateTime.of(2026, 7, 25, 15, 0));
        assertThat(aggregationService.today()).isEqualTo(LocalDate.of(2026, 7, 27));
    }

    @Test
    void normalDayCreatesNoDeviationAlert() {
        givenPlantAndProfile();
        givenAccumulation("150000.00", "15.00", FULL_DAY_SECONDS, 144L);
        givenNoStoredRow();
        givenAlertResults();

        DailyLightAggregationResult result =
                aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        assertThat(result.lightStatus()).isEqualTo(DailyLightStatus.NORMAL);
        assertThat(result.photoperiodStatus()).isEqualTo(DailyLightStatus.NORMAL);
        verify(alertEvaluationService).evaluateDailyMetric(
                eq(PLANT_ID),
                eq(AlertMetricType.DAILY_LIGHT),
                eq(new BigDecimal("150000.00")),
                eq(MIN_LUX_HOUR),
                eq(MAX_LUX_HOUR),
                eq(LocalDateTime.of(2026, 7, 25, 15, 0))
        );
    }

    @Test
    void accumulatedLightBelowMinimumIsLowWhilePhotoperiodStaysNormal() {
        givenPlantAndProfile();
        givenAccumulation("75000.00", "15.00", FULL_DAY_SECONDS, 144L);
        givenNoStoredRow();
        givenAlertResults();

        DailyLightAggregationResult result =
                aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        assertThat(result.lightStatus()).isEqualTo(DailyLightStatus.LOW);
        assertThat(result.photoperiodStatus()).isEqualTo(DailyLightStatus.NORMAL);
    }

    @Test
    void photoperiodBeyondToleranceIsHighWhileAccumulatedLightStaysNormal() {
        // 목표 15시간의 허용 범위는 12 ~ 18시간이다. 총량이 정상이어도 일조가 길면 개화가 교란된다.
        givenPlantAndProfile();
        givenAccumulation("150000.00", "20.00", FULL_DAY_SECONDS, 144L);
        givenNoStoredRow();
        givenAlertResults();

        DailyLightAggregationResult result =
                aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        assertThat(result.lightStatus()).isEqualTo(DailyLightStatus.NORMAL);
        assertThat(result.photoperiodStatus()).isEqualTo(DailyLightStatus.HIGH);
        verify(alertEvaluationService).evaluateDailyMetric(
                eq(PLANT_ID),
                eq(AlertMetricType.PHOTOPERIOD),
                eq(new BigDecimal("20.00")),
                eq(new BigDecimal("12.000")),
                eq(new BigDecimal("18.000")),
                any(LocalDateTime.class)
        );
    }

    @Test
    void lowCoverageSkipsJudgementAndDoesNotTouchAlerts() {
        givenPlantAndProfile();
        // 장치가 대부분 조용했다. 이 값으로 광량 부족을 단정하면 오탐이다.
        givenAccumulation("9000.00", "1.00", 6000L, 11L);
        givenNoStoredRow();

        DailyLightAggregationResult result =
                aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        assertThat(result.lightStatus()).isEqualTo(DailyLightStatus.INSUFFICIENT_DATA);
        assertThat(result.photoperiodStatus()).isEqualTo(DailyLightStatus.INSUFFICIENT_DATA);
        verify(alertEvaluationService, never()).evaluateDailyMetric(
                anyString(), any(), any(), any(), any(), any());

        ArgumentCaptor<PlantDailyLight> captor = ArgumentCaptor.forClass(PlantDailyLight.class);
        verify(dailyLightRepository).save(captor.capture());
        assertThat(captor.getValue().getCoveragePct()).isEqualByComparingTo("6.94");
    }

    @Test
    void storedRowKeepsThresholdSnapshotAndIsReusedOnRecompute() {
        givenPlantAndProfile();
        givenAccumulation("150000.00", "15.00", FULL_DAY_SECONDS, 144L);
        PlantDailyLight existing = PlantDailyLight.create(PLANT_ID, LIGHT_DATE);
        when(dailyLightRepository.findByPlantIdAndLightDate(PLANT_ID, LIGHT_DATE))
                .thenReturn(Optional.of(existing));
        givenAlertResults();

        aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        verify(dailyLightRepository).save(existing);
        assertThat(existing.getTargetLuxHour()).isEqualByComparingTo(TARGET_LUX_HOUR);
        assertThat(existing.getThresholdMinLuxHour()).isEqualByComparingTo(MIN_LUX_HOUR);
        assertThat(existing.getThresholdMaxLuxHour()).isEqualByComparingTo(MAX_LUX_HOUR);
        assertThat(existing.getTargetPhotoperiodHours()).isEqualByComparingTo(PHOTOPERIOD);
        assertThat(existing.getComputedAt()).isEqualTo(LocalDateTime.of(2026, 7, 26, 17, 0));
    }

    @Test
    void deletedPlantIsSkipped() {
        Plant plant = mock(Plant.class);
        when(plant.getStatus()).thenReturn(PlantStatus.DELETED);
        when(plantRepository.findById(PLANT_ID)).thenReturn(Optional.of(plant));

        DailyLightAggregationResult result =
                aggregationService.aggregateAndStore(PLANT_ID, LIGHT_DATE);

        assertThat(result.lightStatus()).isEqualTo(DailyLightStatus.NOT_APPLICABLE);
        verify(dailyLightRepository, never()).save(any());
    }

    private void givenPlantAndProfile() {
        Plant plant = mock(Plant.class);
        when(plant.getStatus()).thenReturn(PlantStatus.ACTIVE);
        when(plantRepository.findById(PLANT_ID)).thenReturn(Optional.of(plant));

        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getDailyLightTargetLuxHour()).thenReturn(TARGET_LUX_HOUR);
        when(profile.getDailyLightMinLuxHour()).thenReturn(MIN_LUX_HOUR);
        when(profile.getDailyLightMaxLuxHour()).thenReturn(MAX_LUX_HOUR);
        when(profile.getPhotoperiodHours()).thenReturn(PHOTOPERIOD);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    private void givenAccumulation(
            String accumulatedLuxHour,
            String lightHours,
            long coveredSeconds,
            long sampleCount
    ) {
        DailyLightAccumulation accumulation = mock(DailyLightAccumulation.class);
        when(accumulation.getAccumulatedLuxHour()).thenReturn(new BigDecimal(accumulatedLuxHour));
        when(accumulation.getLightHours()).thenReturn(new BigDecimal(lightHours));
        when(accumulation.getCoveredSeconds()).thenReturn(coveredSeconds);
        when(accumulation.getSampleCount()).thenReturn(sampleCount);
        when(sensorReadingRepository.aggregateDailyLight(
                eq(PLANT_ID),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                eq(600L)))
                .thenReturn(accumulation);
    }

    private void givenNoStoredRow() {
        when(dailyLightRepository.findByPlantIdAndLightDate(PLANT_ID, LIGHT_DATE))
                .thenReturn(Optional.empty());
    }

    private void givenAlertResults() {
        when(alertEvaluationService.evaluateDailyMetric(
                anyString(), any(), any(), any(), any(), any()))
                .thenReturn(AlertEvaluationResult.UNCHANGED);
    }
}
