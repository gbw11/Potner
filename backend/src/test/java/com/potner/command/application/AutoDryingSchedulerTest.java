package com.potner.command.application;

import com.potner.alert.domain.Alert;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.alert.domain.AlertRepository;
import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.location.domain.RobotLocationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutoDryingSchedulerTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String REQUEST_ID = "40000000-0000-0000-0000-0000000000dd";
    private static final Instant NOW = Instant.parse("2026-07-31T05:00:00Z");
    private static final int MAX_RUNS = 6;

    @Mock
    private AlertRepository alertRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private RobotBusyGuard busyGuard;

    @Mock
    private DeviceCommandService commandService;

    private AutoDryingScheduler scheduler(boolean enabled) {
        return new AutoDryingScheduler(
                alertRepository,
                commandRepository,
                busyGuard,
                commandService,
                new DeviceCommandProperties(
                        120, 30, 30, true, true, 8, 17, 60, true, 10, 16, enabled, 600, MAX_RUNS),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void overwateredPlantIsTakenToTheStation() {
        // 팬이 스테이션에 고정이라 송풍도 이동이 앞선다.
        givenOverwatered(PLANT_ID);
        givenRunsToday(0);
        givenLastNavigate(null);

        assertThat(scheduler(true).dryOnce()).isEqualTo(1);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.WATER_STATION);
    }

    @Test
    void alreadyAtTheStationFansWithoutMovingAgain() {
        // 말리는 동안 오가면 이동이 송풍보다 오래 걸린다. 그 자리에서 반복한다.
        givenOverwatered(PLANT_ID);
        givenRunsToday(1);
        givenLastNavigate(navigateTo(RobotLocationType.WATER_STATION));

        scheduler(true).dryOnce();

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.FAN);
    }

    @Test
    void arrivalAtTheStationTriggersFanning() {
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.DRYING,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.OK));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.FAN);
    }

    @Test
    void fanCompletionDoesNotSendTheRobotHomeByItself() {
        // 아직 과습일 수 있다. 복귀는 알림이 닫힌 것을 확인한 주기 검사가 맡는다.
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.FAN, CommandPurpose.DRYING, null, DeviceCommandStatus.OK));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void driedPlantStillAtTheStationIsBroughtHome() {
        // 과습이 풀리면 알림이 닫히는데 해제는 이벤트를 내지 않는다. 이 단계가 없으면 로봇이
        // 스테이션에 방치된다.
        when(alertRepository.findAllByMetricTypeAndDeviationAndResolvedAtIsNull(
                AlertMetricType.SOIL_MOISTURE, AlertDeviation.HIGH)).thenReturn(List.of());
        when(commandRepository.findPlantIdsByPurposeSince(eq(CommandPurpose.DRYING), any()))
                .thenReturn(List.of(PLANT_ID));
        givenLastNavigate(navigateTo(RobotLocationType.WATER_STATION));

        assertThat(scheduler(true).dryOnce()).isEqualTo(1);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void driedPlantAlreadyHomeIsLeftAlone() {
        when(alertRepository.findAllByMetricTypeAndDeviationAndResolvedAtIsNull(
                AlertMetricType.SOIL_MOISTURE, AlertDeviation.HIGH)).thenReturn(List.of());
        when(commandRepository.findPlantIdsByPurposeSince(eq(CommandPurpose.DRYING), any()))
                .thenReturn(List.of(PLANT_ID));
        givenLastNavigate(navigateTo(RobotLocationType.HOME));

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void stopsAtTheDailyLimit() {
        // 정상 종료는 알림이 닫히는 것이다. 이 상한은 수분 센서가 고장 나 계속 높은 값을 보낼 때
        // 팬이 끝없이 도는 것을 막는다.
        givenOverwatered(PLANT_ID);
        givenRunsToday(MAX_RUNS);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void yieldsWhenAnotherChainIsUsingTheRobot() {
        givenOverwatered(PLANT_ID);
        when(busyGuard.isBusy(PLANT_ID)).thenReturn(true);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void doesNotContinueOtherChains() {
        // 급수 체인의 스테이션 도착에 송풍을 붙이면 방금 준 물을 곧바로 말린다.
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.WATERING,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.OK));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void disabledFlagStopsEverything() {
        scheduler(false).dry();

        verify(alertRepository, never())
                .findAllByMetricTypeAndDeviationAndResolvedAtIsNull(any(), any());
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void oneFailingPlantDoesNotBlockTheOthers() {
        givenOverwatered(PLANT_ID);
        givenRunsToday(0);
        givenLastNavigate(null);
        when(commandService.issueAuto(eq(PLANT_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        assertThat(scheduler(true).dryOnce()).isZero();
    }

    private DeviceCommandCompletedEvent completed(
            DeviceCommandType type,
            CommandPurpose purpose,
            RobotLocationType destination,
            DeviceCommandStatus status
    ) {
        return new DeviceCommandCompletedEvent(
                REQUEST_ID, PLANT_ID, type, CommandInitiator.AUTO, purpose, destination, status);
    }

    private void givenOverwatered(String plantId) {
        Alert alert = Alert.openStationWaterLow(USER_ID, plantId, LocalDateTime.now(
                Clock.fixed(NOW, ZoneOffset.UTC)));
        lenient().when(alertRepository.findAllByMetricTypeAndDeviationAndResolvedAtIsNull(
                AlertMetricType.SOIL_MOISTURE, AlertDeviation.HIGH)).thenReturn(List.of(alert));
        lenient().when(commandRepository.findPlantIdsByPurposeSince(any(), any()))
                .thenReturn(List.of());
    }

    private void givenRunsToday(long runs) {
        lenient().when(commandRepository.countByPlantIdAndCommandTypeAndPurposeAndIssuedAtAfter(
                eq(PLANT_ID), eq(DeviceCommandType.FAN), eq(CommandPurpose.DRYING), any()))
                .thenReturn(runs);
    }

    private void givenLastNavigate(DeviceCommand command) {
        lenient().when(commandRepository
                        .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                                PLANT_ID, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK))
                .thenReturn(Optional.ofNullable(command));
    }

    private DeviceCommand navigateTo(RobotLocationType destination) {
        DeviceCommand command = mock(DeviceCommand.class);
        lenient().when(command.getDestination()).thenReturn(destination);
        return command;
    }
}
