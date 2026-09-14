package com.potner.command.application;

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
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import com.potner.sensor.domain.SensorQuality;
import com.potner.sensor.domain.SensorReading;
import com.potner.sensor.domain.SensorReadingRepository;
import com.potner.sensor.domain.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String REQUEST_ID = "40000000-0000-0000-0000-0000000000dd";

    /** KST 14시. 환기 시간대(8~22시) 한가운데다. */
    private static final Instant NOW = Instant.parse("2026-07-31T05:00:00Z");

    private static final int MAX_RUNS = 6;
    private static final int INTERVAL_SECONDS = 9000;
    private static final int MIN_TEMPERATURE_C = 15;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private RobotBusyGuard busyGuard;

    @Mock
    private DeviceCommandService commandService;

    private AutoDryingScheduler scheduler(boolean enabled) {
        return scheduler(enabled, 8, 22);
    }

    private AutoDryingScheduler scheduler(boolean enabled, int windowStart, int windowEnd) {
        return new AutoDryingScheduler(
                assignmentRepository,
                commandRepository,
                sensorReadingRepository,
                busyGuard,
                commandService,
                new DeviceCommandProperties(
                        120, 30, 30, true, true, 8, 17, 60, true, 10, 16,
                        enabled, 600, INTERVAL_SECONDS, windowStart, windowEnd,
                        MIN_TEMPERATURE_C, MAX_RUNS),
                new SensorQueryProperties("+09:00", 15, 7, 90),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void ventilatesOnScheduleWithoutAnyHumidityAlert() {
        // 공기 순환의 이득 절반은 습도와 무관하다. 습도가 정상인 날에도 돌아야 한다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
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
    void waitsUntilTheIntervalHasElapsed() {
        // 간격이 하루 가동 횟수를 정한다. 검사 주기마다 돌리면 하루치가 한 시간에 몰린다.
        givenTargets(PLANT_ID);
        givenLastFan(fanIssuedSecondsAgo(INTERVAL_SECONDS - 60));

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void countsTheWateringChainFanTowardTheInterval() {
        // 급수 체인이 방금 팬을 돌렸으면 주기 환기도 쉰다. 목적별로 세면 급수한 날 송풍이
        // 두 배가 된다 — 같은 팬 하나를 두 체인이 나눠 쓰기 때문이다.
        givenTargets(PLANT_ID);
        DeviceCommand wateringFan = fanIssuedSecondsAgo(60);
        lenient().when(wateringFan.getPurpose()).thenReturn(CommandPurpose.WATERING);
        givenLastFan(wateringFan);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void fansWithoutMovingWhenTheRobotIsAlreadyAtTheStation() {
        // 다른 체인이 두고 간 경우다. 다시 데려갈 것 없이 그 자리에서 돌린다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
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
    void fanCompletionSendsTheRobotHome() {
        // 송풍 사이가 몇 시간씩 벌어지므로 스테이션에 머물 이유가 없다. 왕복 몇 분이 아까워
        // 로봇을 몇 시간 세워 두지 않는다.
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.FAN, CommandPurpose.DRYING, null, DeviceCommandStatus.OK));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void robotLeftAtTheStationIsBroughtHome() {
        // 송풍이 실패하면 체인이 거기서 멈춘다. 이 단계가 없으면 로봇이 스테이션에 방치된다.
        givenTargets();
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
    void robotAlreadyHomeIsLeftAlone() {
        givenTargets();
        when(commandRepository.findPlantIdsByPurposeSince(eq(CommandPurpose.DRYING), any()))
                .thenReturn(List.of(PLANT_ID));
        givenLastNavigate(navigateTo(RobotLocationType.HOME));

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void returnsTheRobotEvenOutsideTheVentilationWindow() {
        // 환기를 멈추는 것과 로봇을 밤새 스테이션에 세워 두는 것은 다른 이야기다.
        givenTargets(PLANT_ID);
        when(commandRepository.findPlantIdsByPurposeSince(eq(CommandPurpose.DRYING), any()))
                .thenReturn(List.of(PLANT_ID));
        givenLastNavigate(navigateTo(RobotLocationType.WATER_STATION));

        // 창을 0~1시로 좁혀 현재 시각(KST 14시)을 밖으로 만든다.
        assertThat(scheduler(true, 0, 1).dryOnce()).isEqualTo(1);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), eq(CommandPurpose.DRYING));
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void doesNotVentilateOutsideTheWindow() {
        // 밤에는 기공이 닫혀 증산이 없고 팬 소음만 남는다.
        givenTargets(PLANT_ID);

        assertThat(scheduler(true, 0, 1).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void doesNotVentilateWhenItIsTooCold() {
        // 찬 바람은 냉해를 부르고, 저온에서는 곰팡이 위험도 낮다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
        givenRunsToday(0);
        givenTemperature(BigDecimal.valueOf(12), 0);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void ventilatesWhenTheTemperatureIsUnknown() {
        // 온도 센서 하나가 죽었다고 환기가 통째로 멈추면 손해가 더 크다. 송풍은 잘못 돌아도
        // 해가 적은 동작이라 막지 않는 쪽으로 실패한다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
        givenRunsToday(0);
        givenLastNavigate(null);
        when(sensorReadingRepository
                .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                        PLANT_ID, SensorType.TEMPERATURE, SensorQuality.GOOD))
                .thenReturn(Optional.empty());

        assertThat(scheduler(true).dryOnce()).isEqualTo(1);
    }

    @Test
    void ignoresAStaleTemperatureReading() {
        // 신선도 창(15분)을 넘긴 값으로 판단하면 어제의 추위로 오늘 환기가 막힌다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
        givenRunsToday(0);
        givenLastNavigate(null);
        givenTemperature(BigDecimal.valueOf(12), 60);

        assertThat(scheduler(true).dryOnce()).isEqualTo(1);
    }

    @Test
    void stopsAtTheDailyLimit() {
        // 평소에는 간격이 횟수를 정하므로 닿지 않는다. 설정을 잘못 잡았을 때의 안전장치다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
        givenRunsToday(MAX_RUNS);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void countsTheDailyLimitFromServiceMidnightNotUtc() {
        // UTC 자정으로 끊으면 KST 오전 9시에 리셋되어 "어젯밤과 오늘 아침"이 한 묶음이 된다.
        givenTargets(PLANT_ID);
        givenLastFan(null);
        givenRunsToday(0);
        givenLastNavigate(null);

        scheduler(true).dryOnce();

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(commandRepository).countByPlantIdAndCommandTypeAndIssuedAtAfter(
                eq(PLANT_ID), eq(DeviceCommandType.FAN), since.capture());
        // KST 2026-07-31 00:00 == UTC 2026-07-30 15:00
        assertThat(since.getValue()).isEqualTo(LocalDateTime.parse("2026-07-30T15:00:00"));
    }

    @Test
    void yieldsWhenAnotherChainIsUsingTheRobot() {
        givenTargets(PLANT_ID);
        when(busyGuard.isBusy(PLANT_ID)).thenReturn(true);

        assertThat(scheduler(true).dryOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void doesNotContinueOtherChains() {
        // 급수 체인의 스테이션 도착에 송풍을 붙이면 그 체인이 두 번 돌린다.
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.WATERING,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.OK));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void failedStepDoesNotContinueTheChain() {
        scheduler(true).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.DRYING,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.ERROR));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void disabledFlagStopsEverything() {
        scheduler(false).dry();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void oneFailingPlantDoesNotBlockTheOthers() {
        givenTargets(PLANT_ID);
        givenLastFan(null);
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

    private void givenTargets(String... plantIds) {
        List<AssignedDeviceView> views = java.util.Arrays.stream(plantIds)
                .map(plantId -> {
                    AssignedDeviceView view = mock(AssignedDeviceView.class);
                    lenient().when(view.getPlantId()).thenReturn(plantId);
                    return view;
                })
                .map(AssignedDeviceView.class::cast)
                .toList();
        lenient().when(assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.RASPBERRY_PI, PlantStatus.DELETED)).thenReturn(views);
        lenient().when(commandRepository.findPlantIdsByPurposeSince(any(), any()))
                .thenReturn(List.of());
    }

    private void givenLastFan(DeviceCommand command) {
        lenient().when(commandRepository.findFirstByPlantIdAndCommandTypeOrderByIssuedAtDesc(
                PLANT_ID, DeviceCommandType.FAN)).thenReturn(Optional.ofNullable(command));
    }

    private DeviceCommand fanIssuedSecondsAgo(long seconds) {
        DeviceCommand command = mock(DeviceCommand.class);
        lenient().when(command.getIssuedAt()).thenReturn(
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(seconds));
        return command;
    }

    private void givenRunsToday(long runs) {
        lenient().when(commandRepository.countByPlantIdAndCommandTypeAndIssuedAtAfter(
                eq(PLANT_ID), eq(DeviceCommandType.FAN), any())).thenReturn(runs);
    }

    private void givenTemperature(BigDecimal celsius, long minutesAgo) {
        SensorReading reading = mock(SensorReading.class);
        lenient().when(reading.getMeasuredValue()).thenReturn(celsius);
        lenient().when(reading.getMeasuredAt()).thenReturn(
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(minutesAgo));
        lenient().when(sensorReadingRepository
                        .findFirstByPlantIdAndSensorTypeAndQualityOrderByMeasuredAtDesc(
                                PLANT_ID, SensorType.TEMPERATURE, SensorQuality.GOOD))
                .thenReturn(Optional.of(reading));
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
