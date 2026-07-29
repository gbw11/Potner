package com.potner.happiness.application;

import com.potner.bloom.domain.PlantBloomRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDeviceType;
import com.potner.happiness.config.HappinessProperties;
import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HappinessServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    /** 한국 시간으로는 2026-07-29 00:30 이다. UTC 로 끊으면 개화 날짜가 하루 밀린다. */
    private static final Instant NOW = Instant.parse("2026-07-28T15:30:00Z");
    private static final LocalDate SERVICE_TODAY = LocalDate.of(2026, 7, 29);
    private static final LocalDateTime FRESH = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
            .minusMinutes(1);

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private PlantBloomRepository bloomRepository;

    private HappinessService happinessService;

    @BeforeEach
    void setUp() {
        happinessService = new HappinessService(
                plantRepository,
                profileRepository,
                sensorReadingRepository,
                bloomRepository,
                new HappinessProperties(
                        true,
                        new BigDecimal("0.5"),
                        30,
                        IotDeviceType.JETSON_ORIN
                ),
                new SensorQueryProperties("+09:00", 15, 7, 90),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void normalTemperatureAndHumidityGiveTheNeutralFace() {
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "70", FRESH);
        givenNoReading(SensorType.ILLUMINANCE);
        givenNoBloomToday();

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.NEUTRAL);
        assertThat(state.reason()).isEqualTo(ExpressionReason.NONE);
    }

    @Test
    void temperatureOutOfRangeGivesTheSadFace() {
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "35", FRESH);
        givenNoReading(SensorType.ILLUMINANCE);
        givenNoBloomToday();

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.SAD);
        assertThat(state.reason()).isEqualTo(ExpressionReason.TEMPERATURE);
    }

    @Test
    void humidityOutOfRangeGivesTheSadFace() {
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "20", FRESH);
        givenNoReading(SensorType.ILLUMINANCE);
        givenNoBloomToday();

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.SAD);
        assertThat(state.reason()).isEqualTo(ExpressionReason.HUMIDITY);
    }

    @Test
    void halfOfTheTargetLuxCountsAsSunlightButFluorescentLightDoesNot() {
        // 목표 10,000 lux × 0.5 = 5,000 lux. 창가 맑은 날은 넘고 형광등(300~500)은 못 넘는다.
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "70", FRESH);
        givenNoBloomToday();

        givenReading(SensorType.ILLUMINANCE, "5000", FRESH);
        assertThat(happinessService.judge(PLANT_ID).reason()).isEqualTo(ExpressionReason.SUNLIGHT);

        givenReading(SensorType.ILLUMINANCE, "400", FRESH);
        assertThat(happinessService.judge(PLANT_ID).reason()).isEqualTo(ExpressionReason.NONE);
    }

    @Test
    void sunlightWinsOverBadTemperatureButTheBaselineStaysSad() {
        // 부스트가 바탕을 이긴다. 다만 밤이 되면 우울로 떨어진다는 사실은 남겨야 한다.
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "35", FRESH);
        givenReading(SensorType.ILLUMINANCE, "9000", FRESH);
        givenNoBloomToday();

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.HAPPY);
        assertThat(state.reason()).isEqualTo(ExpressionReason.SUNLIGHT);
        assertThat(state.baseline()).isEqualTo(PlantExpression.SAD);
    }

    @Test
    void bloomingTodayBeatsSunlightSoTheDayIsAboutTheFlower() {
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "70", FRESH);
        when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, SERVICE_TODAY)).thenReturn(true);

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.HAPPY);
        assertThat(state.reason()).isEqualTo(ExpressionReason.BLOOMED);
    }

    @Test
    void bloomDateUsesTheServiceTimezoneNotUtc() {
        // UTC 로 계산하면 07-28 을 물어봐서, 한국 시간으로 오늘 핀 꽃을 놓친다.
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "70", FRESH);
        givenNoReading(SensorType.ILLUMINANCE);
        when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, SERVICE_TODAY)).thenReturn(false);

        happinessService.judge(PLANT_ID);

        org.mockito.Mockito.verify(bloomRepository)
                .existsByPlantIdAndBloomDate(PLANT_ID, LocalDate.of(2026, 7, 29));
    }

    @Test
    void staleReadingsDoNotDecideTheFace() {
        // 로봇이 꺼져 있던 동안의 값으로 우울을 표시하면 이미 지난 문제를 계속 보여준다.
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "35", FRESH.minusMinutes(30));
        givenNoReading(SensorType.HUMIDITY);
        givenNoReading(SensorType.ILLUMINANCE);
        givenNoBloomToday();

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.NEUTRAL);
        assertThat(state.reason()).isEqualTo(ExpressionReason.NONE);
    }

    @Test
    void missingProfileDoesNotBreakTheBatch() {
        // 주기 작업이 여러 식물을 도는 중에 한 건의 예외로 멈추면 안 된다.
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.empty());

        PlantExpressionState state = happinessService.judge(PLANT_ID);

        assertThat(state.expression()).isEqualTo(PlantExpression.NEUTRAL);
        assertThat(state.reason()).isEqualTo(ExpressionReason.NONE);
    }

    @Test
    void otherUsersPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> happinessService.getHappiness(USER_ID, PLANT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PLANT_NOT_FOUND));
    }

    @Test
    void ownerSeesTheJudgedFace() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
        givenProfile();
        givenReading(SensorType.TEMPERATURE, "24", FRESH);
        givenReading(SensorType.HUMIDITY, "70", FRESH);
        givenReading(SensorType.ILLUMINANCE, "20000", FRESH);
        givenNoBloomToday();

        var response = happinessService.getHappiness(USER_ID, PLANT_ID);

        assertThat(response.plantId()).isEqualTo(PLANT_ID);
        assertThat(response.expression()).isEqualTo(PlantExpression.HAPPY);
        assertThat(response.reason()).isEqualTo(ExpressionReason.SUNLIGHT);
        assertThat(response.baseline()).isEqualTo(PlantExpression.NEUTRAL);
    }

    /** 시드된 바질 발아기 기준을 흉내낸다. 엔티티에 세터가 없어 목으로 만든다. */
    private void givenProfile() {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        lenient().when(profile.getTemperatureMinC()).thenReturn(new BigDecimal("21"));
        lenient().when(profile.getTemperatureMaxC()).thenReturn(new BigDecimal("29"));
        lenient().when(profile.getHumidityMinPct()).thenReturn(new BigDecimal("60"));
        lenient().when(profile.getHumidityMaxPct()).thenReturn(new BigDecimal("80"));
        lenient().when(profile.getIlluminanceTargetLux()).thenReturn(new BigDecimal("10000"));
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    private void givenReading(SensorType sensorType, String value, LocalDateTime measuredAt) {
        SensorReading reading = mock(SensorReading.class);
        lenient().when(reading.getMeasuredValue()).thenReturn(new BigDecimal(value));
        lenient().when(reading.getMeasuredAt()).thenReturn(measuredAt);
        lenient().when(sensorReadingRepository
                        .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                                PLANT_ID, sensorType, SensorQuality.GOOD))
                .thenReturn(Optional.of(reading));
    }

    private void givenNoReading(SensorType sensorType) {
        lenient().when(sensorReadingRepository
                        .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                                PLANT_ID, sensorType, SensorQuality.GOOD))
                .thenReturn(Optional.empty());
    }

    private void givenNoBloomToday() {
        lenient().when(bloomRepository.existsByPlantIdAndBloomDate(PLANT_ID, SERVICE_TODAY))
                .thenReturn(false);
    }
}
