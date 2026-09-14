package com.potner.alert.application;

import com.potner.alert.config.AlertProperties;
import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.alert.dto.AlertListResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertQueryServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ALERT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final Instant NOW = Instant.parse("2026-07-26T00:40:00Z");

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private PlantRepository plantRepository;

    private AlertQueryService alertQueryService;

    @BeforeEach
    void setUp() {
        alertQueryService = new AlertQueryService(
                alertRepository,
                plantRepository,
                new AlertProperties(3, new BigDecimal("0.1"), 100, 8),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void alertsAreReturnedWithPlantNameAndUnreadCount() {
        Alert alert = activeAlert();
        givenPage(List.of(alert), 1);
        givenPlantName("내 바질");
        when(alertRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(4L);

        AlertListResponse response = alertQueryService.getMyAlerts(USER_ID, false, false, 0, 20);

        assertThat(response.alerts()).hasSize(1);
        assertThat(response.alerts().getFirst().plantName()).isEqualTo("내 바질");
        assertThat(response.alerts().getFirst().metricType())
                .isEqualTo(AlertMetricType.SOIL_MOISTURE);
        assertThat(response.alerts().getFirst().deviation()).isEqualTo(AlertDeviation.LOW);
        assertThat(response.alerts().getFirst().active()).isTrue();
        assertThat(response.alerts().getFirst().read()).isFalse();
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.unreadCount()).isEqualTo(4);
    }

    @Test
    void emptyPageSkipsPlantLookup() {
        givenPage(List.of(), 0);
        when(alertRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(0L);

        AlertListResponse response = alertQueryService.getMyAlerts(USER_ID, false, false, 0, 20);

        assertThat(response.alerts()).isEmpty();
        verify(plantRepository, never()).findAllById(any());
    }

    @Test
    void filtersArePassedThroughToRepository() {
        givenPage(List.of(), 0);
        when(alertRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(0L);

        alertQueryService.getMyAlerts(USER_ID, true, true, 2, 5);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(alertRepository).findOwned(eq(USER_ID), eq(true), eq(true), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void pageSizeAboveLimitIsClampedAndReported() {
        givenPage(List.of(), 0);
        when(alertRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(0L);

        AlertListResponse response = alertQueryService.getMyAlerts(USER_ID, false, false, 0, 500);

        assertThat(response.size()).isEqualTo(100);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(alertRepository).findOwned(eq(USER_ID), anyBoolean(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void invalidPagingIsRejected() {
        assertThatThrownBy(() -> alertQueryService.getMyAlerts(USER_ID, false, false, -1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> alertQueryService.getMyAlerts(USER_ID, false, false, 0, 0))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void markReadRecordsFirstConfirmationOnly() {
        Alert alert = activeAlert();
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.of(alert));

        alertQueryService.markRead(USER_ID, ALERT_ID);
        LocalDateTime firstReadAt = alert.getReadAt();
        alertQueryService.markRead(USER_ID, ALERT_ID);

        assertThat(firstReadAt).isEqualTo(LocalDateTime.of(2026, 7, 26, 0, 40));
        assertThat(alert.getReadAt()).isEqualTo(firstReadAt);
        assertThat(alert.isRead()).isTrue();
    }

    /**
     * 목록에서 사라졌는데 안 읽음으로 남으면 홈 배지만 켜져 있고 사용자는 무엇이 남았는지 찾을
     * 수 없다. 배지는 {@code countByUserIdAndReadAtIsNull} 로 세므로 읽음을 함께 남겨야 빠진다.
     */
    @Test
    void dismissAlsoMarksReadSoTheBadgeDoesNotStayOn() {
        Alert alert = activeAlert();
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.of(alert));

        alertQueryService.dismiss(USER_ID, ALERT_ID);

        assertThat(alert.isDismissed()).isTrue();
        assertThat(alert.isRead()).isTrue();
    }

    /**
     * 치우기는 "이상이 해소됨" 이 아니다. resolvedAt 을 건드리면 자동 급수·말리기가 알림을 밀어낸
     * 것을 해소로 읽어 체인을 다시 시작한다.
     */
    @Test
    void dismissDoesNotResolveTheAlert() {
        Alert alert = activeAlert();
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.of(alert));

        alertQueryService.dismiss(USER_ID, ALERT_ID);

        assertThat(alert.getResolvedAt()).isNull();
        assertThat(alert.isActive()).isTrue();
    }

    @Test
    void dismissKeepsTheFirstDismissedAt() {
        Alert alert = activeAlert();
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.of(alert));

        alertQueryService.dismiss(USER_ID, ALERT_ID);
        LocalDateTime first = alert.getDismissedAt();
        alertQueryService.dismiss(USER_ID, ALERT_ID);

        assertThat(first).isEqualTo(LocalDateTime.of(2026, 7, 26, 0, 40));
        assertThat(alert.getDismissedAt()).isEqualTo(first);
    }

    /** 되돌리기는 목록에만 되살린다. 실제로 봤다는 사실은 되돌리기로 사라지지 않는다. */
    @Test
    void restoreBringsItBackButKeepsRead() {
        Alert alert = activeAlert();
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.of(alert));
        alertQueryService.dismiss(USER_ID, ALERT_ID);

        alertQueryService.restore(USER_ID, ALERT_ID);

        assertThat(alert.isDismissed()).isFalse();
        assertThat(alert.isRead()).isTrue();
    }

    @Test
    void dismissRejectsAlertOwnedByAnotherUser() {
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertQueryService.dismiss(USER_ID, ALERT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.ALERT_NOT_FOUND);
    }

    @Test
    void markReadRejectsAlertOwnedByAnotherUser() {
        when(alertRepository.findByIdAndUserId(ALERT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> alertQueryService.markRead(USER_ID, ALERT_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(ErrorCode.ALERT_NOT_FOUND);
    }

    private void givenPage(List<Alert> content, long total) {
        when(alertRepository.findOwned(eq(USER_ID), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, PageRequest.of(0, 20), total));
    }

    private void givenPlantName(String nickname) {
        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn(PLANT_ID);
        when(plant.getNickname()).thenReturn(nickname);
        when(plantRepository.findAllById(List.of(PLANT_ID))).thenReturn(List.of(plant));
    }

    private Alert activeAlert() {
        return Alert.open(
                USER_ID,
                PLANT_ID,
                AlertMetricType.SOIL_MOISTURE,
                AlertDeviation.LOW,
                new BigDecimal("34.00"),
                new BigDecimal("40.00"),
                new BigDecimal("55.00"),
                LocalDateTime.of(2026, 7, 26, 0, 39)
        );
    }
}
