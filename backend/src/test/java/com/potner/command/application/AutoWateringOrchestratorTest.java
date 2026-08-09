package com.potner.command.application;

import com.potner.alert.application.AlertOpenedEvent;
import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;
import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
class AutoWateringOrchestratorTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String REQUEST_ID = "40000000-0000-0000-0000-0000000000dd";

    @Mock
    private DeviceCommandService commandService;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private RobotLocationRepository locationRepository;

    private AutoWateringOrchestrator orchestrator(boolean autoWaterEnabled) {
        return orchestrator(autoWaterEnabled, true);
    }

    private AutoWateringOrchestrator orchestrator(
            boolean autoWaterEnabled,
            boolean autoDryingEnabled
    ) {
        return new AutoWateringOrchestrator(
                commandService,
                profileRepository,
                assignmentRepository,
                locationRepository,
                new DeviceCommandProperties(
                        120, 30, 30, autoWaterEnabled, true, 8, 17, 60, true, 10, 16,
                        autoDryingEnabled, 600, 9000, 8, 22, 15, 6)
        );
    }

    @Test
    void soilMoistureLowStartsTheRunByNavigatingToTheStation() {
        givenPlantReadyToWater(false);

        orchestrator(true).onAlertOpened(soilMoistureLowAlert());

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.WATER_STATION);
    }

    @Test
    void otherMetricsAndDirectionsDoNotStartTheRun() {
        // 과습(HIGH)에 급수를 보내면 상태를 악화시킨다. 온도 알림도 급수와 무관하다.
        orchestrator(true).onAlertOpened(alert(AlertMetricType.SOIL_MOISTURE, AlertDeviation.HIGH));
        orchestrator(true).onAlertOpened(alert(AlertMetricType.TEMPERATURE, AlertDeviation.LOW));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void disabledFlagStopsBothStartAndContinuation() {
        // 오작동하는 로봇을 세울 때 재배포 없이 플래그만 내리면 되어야 한다. 진행 중인
        // 체인도 다음 회신에서 멈춘다.
        orchestrator(false).onAlertOpened(soilMoistureLowAlert());
        orchestrator(false).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.AUTO,
                RobotLocationType.WATER_STATION,
                DeviceCommandStatus.OK
        ));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void skipsTheRunWhenNoWateringAmountIsConfigured() {
        // 이동 명령 자체는 급수량 없이도 발행된다. 지금 안 거르면 로봇이 스테이션까지 간
        // 뒤에야 급수 단계가 실패한다.
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getRecommendedWateringMl()).thenReturn(null);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));

        orchestrator(true).onAlertOpened(soilMoistureLowAlert());

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void skipsTheRunWhenTheStationReportsWaterLow() {
        // 저수조가 비어 있으면 가 봐야 급수가 안 된다. 사용자는 이미 물 부족 알림을 받았다.
        givenPlantReadyToWater(true);

        orchestrator(true).onAlertOpened(soilMoistureLowAlert());

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void swallowsIssueFailuresSoTheAlertPipelineIsNotAffected() {
        // 앞선 명령 대기 중(409) 등. 예외가 리스너 밖으로 나가면 알림 파이프라인 로그가 오염된다.
        givenPlantReadyToWater(false);
        when(commandService.issueAuto(eq(PLANT_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.DEVICE_COMMAND_ALREADY_PENDING));

        orchestrator(true).onAlertOpened(soilMoistureLowAlert());
    }

    @Test
    void arrivalAtTheStationTriggersWatering() {
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.AUTO,
                RobotLocationType.WATER_STATION,
                DeviceCommandStatus.OK
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.WATER);
    }

    @Test
    void wateringSuccessVentilatesBeforeGoingHome() {
        // 급수 직후가 표토와 잎이 젖어 곰팡이에 가장 취약한 순간이다. 그때 로봇이 이미 팬이
        // 있는 스테이션에 서 있으므로 이 한 번은 이동이 늘지 않는다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.WATER,
                CommandInitiator.AUTO,
                null,
                DeviceCommandStatus.OK
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.FAN);
    }

    @Test
    void skippedWateringStillVentilatesAndGoesHome() {
        // 과급수 가드가 막으면 라즈베리가 SKIPPED 로 회신한다(dispensedMl=0). 실패가 아니라
        // 물을 주지 않기로 한 판단이고 로봇은 스테이션에 멀쩡히 서 있다. 여기서 체인을 끊으면
        // 급수 버튼을 두 번 누른 것만으로 로봇이 스테이션에 방치된다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.WATER,
                CommandInitiator.AUTO,
                null,
                DeviceCommandStatus.SKIPPED
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.FAN);
    }

    @Test
    void wateringGoesStraightHomeWhenVentilationIsOff() {
        // 환기 플래그 하나로 주기 환기와 급수 뒤 송풍이 함께 멈춰야 한다. 같은 물리 장치다.
        orchestrator(true, false).onCommandCompleted(completed(
                DeviceCommandType.WATER,
                CommandInitiator.AUTO,
                null,
                DeviceCommandStatus.OK
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void ventilationSuccessSendsTheRobotHome() {
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.FAN,
                CommandInitiator.AUTO,
                null,
                DeviceCommandStatus.OK
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void ventilationFailureStillSendsTheRobotHome() {
        // 송풍은 급수에 딸린 부가 단계다. 여기서 멈추면 팬 하나가 고장 났을 때 로봇이
        // 스테이션에 남는다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.FAN,
                CommandInitiator.AUTO,
                null,
                DeviceCommandStatus.ERROR
        ));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void arrivingHomeEndsTheRun() {
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.AUTO,
                RobotLocationType.HOME,
                DeviceCommandStatus.OK
        ));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void failureStopsTheRunWithoutRetry() {
        // 실패한 하드웨어에 명령을 반복하면 급수량 이중 집행 같은 더 나쁜 실패가 생긴다.
        // 수분 부족 알림이 아직 열려 있으므로 사용자는 이미 문제를 알고 있다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.AUTO,
                RobotLocationType.WATER_STATION,
                DeviceCommandStatus.ERROR
        ));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void otherChainsArrivalAtTheStationDoesNotTriggerWatering() {
        // 촬영·말리기 체인도 같은 스테이션 이동을 쓴다. purpose 로 좁히지 않으면 촬영하러
        // 도착한 로봇에 급수 명령이 나간다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.AUTO,
                CommandPurpose.CAPTURE,
                RobotLocationType.WATER_STATION,
                DeviceCommandStatus.OK
        ));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void userIssuedCommandsNeverContinueTheChain() {
        // 사용자가 직접 누른 이동의 회신에 급수를 이어 붙이면, 사용자는 시키지 않은 동작을
        // 보게 된다.
        orchestrator(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE,
                CommandInitiator.USER,
                RobotLocationType.WATER_STATION,
                DeviceCommandStatus.OK
        ));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    private AlertOpenedEvent soilMoistureLowAlert() {
        return alert(AlertMetricType.SOIL_MOISTURE, AlertDeviation.LOW);
    }

    private AlertOpenedEvent alert(AlertMetricType metricType, AlertDeviation deviation) {
        return new AlertOpenedEvent("alert-1", USER_ID, PLANT_ID, "로지", metricType, deviation);
    }

    private DeviceCommandCompletedEvent completed(
            DeviceCommandType type,
            CommandInitiator initiator,
            RobotLocationType destination,
            DeviceCommandStatus status
    ) {
        return completed(type, initiator, CommandPurpose.WATERING, destination, status);
    }

    private DeviceCommandCompletedEvent completed(
            DeviceCommandType type,
            CommandInitiator initiator,
            CommandPurpose purpose,
            RobotLocationType destination,
            DeviceCommandStatus status
    ) {
        return new DeviceCommandCompletedEvent(
                REQUEST_ID, PLANT_ID, type, initiator, purpose, destination, status);
    }

    private void givenPlantReadyToWater(boolean stationWaterLow) {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        lenient().when(profile.getRecommendedWateringMl()).thenReturn(new BigDecimal("350.00"));
        lenient().when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));

        PlantDeviceAssignment assignment = mock(PlantDeviceAssignment.class);
        lenient().when(assignment.getRobotId()).thenReturn(ROBOT_ID);
        lenient().when(assignmentRepository
                        .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(assignment));

        RobotLocation station = mock(RobotLocation.class);
        lenient().when(station.isWaterLow()).thenReturn(stationWaterLow);
        lenient().when(locationRepository
                        .findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.WATER_STATION))
                .thenReturn(Optional.of(station));
    }
}
