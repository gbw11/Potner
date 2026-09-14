package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.user.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DrainageTrayServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final Instant NOW = Instant.parse("2026-07-31T05:00:00Z");

    /** 권장 급수량 350ml × 배수 8 = 임계값 2,800ml */
    private static final BigDecimal WATERING_ML = new BigDecimal("350.00");
    private static final int MULTIPLIER = 8;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DrainageTrayService service;

    @BeforeEach
    void setUp() {
        service = new DrainageTrayService(
                alertRepository,
                commandRepository,
                plantRepository,
                profileRepository,
                new AlertProperties(3, new BigDecimal("0.1"), 100, MULTIPLIER),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void opensAnAlertWhenAccumulatedWaterReachesTheThreshold() {
        givenActivePlant();
        givenWateringAmount();
        givenNoActiveAlert();
        givenNeverEmptied();
        givenAccumulated("2900.00");

        assertThat(service.evaluate(PLANT_ID)).isTrue();

        ArgumentCaptor<Alert> alert = ArgumentCaptor.forClass(Alert.class);
        verify(alertRepository).save(alert.capture());
        assertThat(alert.getValue().getMetricType()).isEqualTo(AlertMetricType.DRAINAGE_TRAY);
        // 트레이는 넘칠 때만 문제다. 방향은 항상 HIGH 다.
        assertThat(alert.getValue().getDeviation()).isEqualTo(AlertDeviation.HIGH);
        // 센서가 아니지만 측정값·기준이 실제로 있다. 앱이 진행 상황을 보여줄 수 있어야 한다.
        assertThat(alert.getValue().getMeasuredValue()).isEqualByComparingTo("2900.00");
        assertThat(alert.getValue().getThresholdMax()).isEqualByComparingTo("2800.00");

        ArgumentCaptor<AlertOpenedEvent> event = ArgumentCaptor.forClass(AlertOpenedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().metricType()).isEqualTo(AlertMetricType.DRAINAGE_TRAY);
        assertThat(event.getValue().plantNickname()).isEqualTo("로지");
    }

    @Test
    void staysQuietBelowTheThreshold() {
        givenActivePlant();
        givenWateringAmount();
        givenNoActiveAlert();
        givenNeverEmptied();
        givenAccumulated("1400.00");

        assertThat(service.evaluate(PLANT_ID)).isFalse();
        verify(alertRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void thresholdScalesWithTheConfiguredWateringAmount() {
        // 같은 누적량이라도 큰 화분은 아직 여유가 있다. 트레이 용량이 화분 크기에 비례하므로
        // 고정 ml 로 판정하면 작은 화분은 늦게, 큰 화분은 일찍 알림이 뜬다.
        givenActivePlant();
        givenNoActiveAlert();
        givenNeverEmptied();
        givenAccumulated("2900.00");

        PlantGrowthProfile bigPot = mock(PlantGrowthProfile.class);
        when(bigPot.getRecommendedWateringMl()).thenReturn(new BigDecimal("500.00"));
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(bigPot));

        // 500 × 8 = 4,000ml 이라 2,900ml 은 아직 미달이다.
        assertThat(service.evaluate(PLANT_ID)).isFalse();
    }

    @Test
    void doesNotOpenASecondAlertWhileOneIsActive() {
        // 비울 때까지 알림은 하나다. 급수를 더 해도 새로 열리지 않는다.
        givenActivePlant();
        when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.of(mock(Alert.class)));

        assertThat(service.evaluate(PLANT_ID)).isFalse();
        verify(alertRepository, never()).save(any());
        // 이미 열려 있으면 누적을 셀 필요도 없다.
        verify(commandRepository, never()).sumDispensedMlAfter(any(), any());
    }

    @Test
    void skipsPlantsWithoutAConfiguredWateringAmount() {
        // 임계값을 만들 근거가 없다. 자동 급수가 같은 이유로 발행하지 않는 것과 같은 방침이다.
        givenActivePlant();
        givenNoActiveAlert();
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getRecommendedWateringMl()).thenReturn(null);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));

        assertThat(service.evaluate(PLANT_ID)).isFalse();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void countsOnlyWaterGivenAfterTheLastEmptying() {
        // 비운 시각이 다음 누적의 기준점이다. 이력을 별도 테이블에 두지 않고 해제 시각을 쓴다.
        givenActivePlant();
        givenWateringAmount();
        givenNoActiveAlert();
        LocalDateTime emptiedAt = LocalDateTime.of(2026, 7, 20, 3, 0);
        Alert previouslyResolved = mock(Alert.class);
        when(previouslyResolved.getResolvedAt()).thenReturn(emptiedAt);
        when(alertRepository
                .findFirstByPlantIdAndMetricTypeAndResolvedAtIsNotNullOrderByResolvedAtDesc(
                        PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.of(previouslyResolved));
        when(commandRepository.sumDispensedMlAfter(PLANT_ID, emptiedAt))
                .thenReturn(new BigDecimal("3000.00"));

        assertThat(service.evaluate(PLANT_ID)).isTrue();
        verify(commandRepository).sumDispensedMlAfter(PLANT_ID, emptiedAt);
    }

    @Test
    void treatsNoWateringRecordAsZero() {
        givenActivePlant();
        givenWateringAmount();
        givenNoActiveAlert();
        givenNeverEmptied();
        // 급수 기록이 없으면 SUM 이 null 이다. 0 으로 다루지 않으면 NPE 가 난다.
        when(commandRepository.sumDispensedMlAfter(eq(PLANT_ID), any())).thenReturn(null);

        assertThat(service.evaluate(PLANT_ID)).isFalse();
    }

    @Test
    void markEmptiedResolvesAndReadsTheActiveAlert() {
        givenOwnedPlant();
        Alert active = Alert.openDrainageTray(
                USER_ID, PLANT_ID, new BigDecimal("2900.00"), new BigDecimal("2800.00"),
                LocalDateTime.of(2026, 7, 30, 1, 0));
        when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.of(active));

        service.markEmptied(USER_ID, PLANT_ID);

        LocalDateTime expectedNow = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        assertThat(active.isActive()).isFalse();
        assertThat(active.getResolvedAt()).isEqualTo(expectedNow);
        // 비웠다는 것은 확인했다는 뜻이다. 읽지 않은 알림에 남아 있으면 사용자가 다시 찾게 된다.
        assertThat(active.isRead()).isTrue();
    }

    @Test
    void markEmptiedWithoutAnActiveAlertIsNotFound() {
        // 비울 필요가 없는 상태에서 호출하면 기준점이 앞당겨져 다음 알림이 늦어진다.
        givenOwnedPlant();
        when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markEmptied(USER_ID, PLANT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    void markEmptiedOnSomeoneElsesPlantIsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markEmptied(USER_ID, PLANT_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(ErrorCode.PLANT_NOT_FOUND));
    }

    private void givenActivePlant() {
        AppUser user = mock(AppUser.class);
        lenient().when(user.getId()).thenReturn(USER_ID);
        Plant plant = mock(Plant.class);
        lenient().when(plant.getUser()).thenReturn(user);
        lenient().when(plant.getNickname()).thenReturn("로지");
        lenient().when(plantRepository.findByIdAndStatusNot(PLANT_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
    }

    private void givenOwnedPlant() {
        lenient().when(plantRepository
                        .findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private void givenWateringAmount() {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        lenient().when(profile.getRecommendedWateringMl()).thenReturn(WATERING_ML);
        lenient().when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    private void givenNoActiveAlert() {
        lenient().when(alertRepository.findByPlantIdAndMetricTypeAndResolvedAtIsNull(
                        PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.empty());
    }

    private void givenNeverEmptied() {
        lenient().when(alertRepository
                        .findFirstByPlantIdAndMetricTypeAndResolvedAtIsNotNullOrderByResolvedAtDesc(
                                PLANT_ID, AlertMetricType.DRAINAGE_TRAY))
                .thenReturn(Optional.empty());
    }

    private void givenAccumulated(String ml) {
        lenient().when(commandRepository.sumDispensedMlAfter(eq(PLANT_ID), any()))
                .thenReturn(new BigDecimal(ml));
    }
}
