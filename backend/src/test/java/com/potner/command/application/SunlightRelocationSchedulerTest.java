package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.light.application.DailyLightAccumulationSnapshot;
import com.potner.light.application.DailyLightAggregationService;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
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
class SunlightRelocationSchedulerTest {

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    /** 한국 시간 10:00 — 햇빛 창(8~17시) 안이다. */
    private static final Instant IN_WINDOW = Instant.parse("2026-07-30T01:00:00Z");

    /** 한국 시간 19:00 — 창이 닫힌 뒤다. */
    private static final Instant AFTER_WINDOW = Instant.parse("2026-07-30T10:00:00Z");

    private static final BigDecimal TARGET = new BigDecimal("5000.00");

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private DailyLightAggregationService aggregationService;

    @Mock
    private RobotBusyGuard busyGuard;

    @Mock
    private DeviceCommandService commandService;

    private SunlightRelocationScheduler scheduler(Instant now) {
        return new SunlightRelocationScheduler(
                assignmentRepository,
                profileRepository,
                commandRepository,
                aggregationService,
                busyGuard,
                commandService,
                new DeviceCommandProperties(120, 30, 30, true, true, 8, 17, 60, true, 10, 16, true, 600, 9000, 8, 22, 15, 6),
                new SensorQueryProperties("+09:00", 30, 7, 90),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    @Test
    void deficitDuringTheDayMovesTheRobotToSunlight() {
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("1200.00");
        givenLastNavigate(null);

        int moved = scheduler(IN_WINDOW).relocateOnce();

        assertThat(moved).isEqualTo(1);
        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.SUNLIGHT);
    }

    @Test
    void fulfilledTargetSendsTheRobotBackToTheShade() {
        // 목표를 채운 뒤에도 햇빛에 세워 두면 과광이 된다. 대기 장소가 그늘이다.
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("5200.00");
        givenLastNavigate(autoNavigate(RobotLocationType.SUNLIGHT));

        scheduler(IN_WINDOW).relocateOnce();

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void windowCloseSendsTheRobotHomeEvenWhenUnfulfilled() {
        // 해가 진 뒤에는 햇빛 자리에 있을 이유가 없다. 미달이어도 되돌린다.
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("1200.00");
        givenLastNavigate(autoNavigate(RobotLocationType.SUNLIGHT));

        scheduler(AFTER_WINDOW).relocateOnce();

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void deficitOutsideTheWindowDoesNotMoveTheRobot() {
        // 밤의 미달은 정상이다. 창 밖에서 내보내면 로봇이 어두운 곳에 서 있게 된다.
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("1200.00");
        givenLastNavigate(null);

        int moved = scheduler(AFTER_WINDOW).relocateOnce();

        assertThat(moved).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void fulfilledAtHomeStaysPut() {
        // 채웠고 이미 그늘이면 옮길 이유가 없다. 왕복이 생기면 안 된다.
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("5200.00");
        givenLastNavigate(autoNavigate(RobotLocationType.HOME));

        assertThat(scheduler(IN_WINDOW).relocateOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void yieldsWhileAnotherChainIsUsingTheRobot() {
        // 이동 대기만 보면 부족하다. 급수·송풍·촬영은 이동이 끝난 뒤 다른 종류의 명령으로
        // 이어지는데, 그 구간에 끼어들면 펌프가 물을 내보내는 중에 바퀴가 움직인다.
        givenAssignedPlant();
        givenDailyLightTarget();
        when(busyGuard.isBusy(PLANT_ID)).thenReturn(true);

        assertThat(scheduler(IN_WINDOW).relocateOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void respectsARecentManualPlacement() {
        // 사용자가 30분 전에 직접 옮겨 두었다(홀드 60분). 자동 재배치가 도로 끌고 가면 안 된다.
        givenAssignedPlant();
        givenDailyLightTarget();
        DeviceCommand manual = navigate(RobotLocationType.GREETING, CommandInitiator.USER,
                LocalDateTime.ofInstant(IN_WINDOW.minusSeconds(1800), ZoneOffset.UTC));
        givenLastNavigate(manual);

        assertThat(scheduler(IN_WINDOW).relocateOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void takesOverAfterTheManualHoldExpires() {
        // 홀드(60분)가 지난 수동 배치는 자동 재배치가 넘겨받는다. 미달이면 햇빛으로 보낸다.
        givenAssignedPlant();
        givenDailyLightTarget();
        givenAccumulated("1200.00");
        DeviceCommand manual = navigate(RobotLocationType.GREETING, CommandInitiator.USER,
                LocalDateTime.ofInstant(IN_WINDOW.minusSeconds(7200), ZoneOffset.UTC));
        givenLastNavigate(manual);

        assertThat(scheduler(IN_WINDOW).relocateOnce()).isEqualTo(1);
    }

    @Test
    void skipsPlantsWithoutADailyLightTarget() {
        // 종 기준에 목표가 없는 식물(저광 관엽 등)은 판정할 근거가 없다.
        givenAssignedPlant();
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));

        assertThat(scheduler(IN_WINDOW).relocateOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    private void givenAssignedPlant() {
        AssignedDeviceView view = mock(AssignedDeviceView.class);
        when(view.getPlantId()).thenReturn(PLANT_ID);
        when(assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.JETSON_ORIN, PlantStatus.DELETED))
                .thenReturn(List.of(view));
    }

    private void givenDailyLightTarget() {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        lenient().when(profile.getDailyLightTargetLuxHour()).thenReturn(TARGET);
        lenient().when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    private void givenAccumulated(String luxHour) {
        lenient().when(aggregationService.accumulateToday(PLANT_ID)).thenReturn(
                new DailyLightAccumulationSnapshot(new BigDecimal(luxHour), BigDecimal.ONE, 3600L, 720L));
    }

    private void givenLastNavigate(DeviceCommand lastNavigate) {
        lenient().when(commandRepository
                        .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                                PLANT_ID, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK))
                .thenReturn(Optional.ofNullable(lastNavigate));
    }

    private DeviceCommand autoNavigate(RobotLocationType destination) {
        return navigate(destination, CommandInitiator.AUTO,
                LocalDateTime.ofInstant(IN_WINDOW.minusSeconds(3600), ZoneOffset.UTC));
    }

    private DeviceCommand navigate(
            RobotLocationType destination,
            CommandInitiator initiator,
            LocalDateTime reportedAt
    ) {
        DeviceCommand command = mock(DeviceCommand.class);
        lenient().when(command.getDestination()).thenReturn(destination);
        lenient().when(command.getInitiator()).thenReturn(initiator);
        lenient().when(command.getReportedAt()).thenReturn(reportedAt);
        return command;
    }
}
