package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.photo.domain.PhotoSource;
import com.potner.photo.domain.PlantPhotoRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCaptureSchedulerTest {

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String OTHER_PLANT_ID = "20000000-0000-0000-0000-0000000000cc";
    private static final String REQUEST_ID = "40000000-0000-0000-0000-0000000000dd";

    /** 한국 시간 12:00 — 촬영 창(10~16시) 안이다. */
    private static final Instant NOON_KST = Instant.parse("2026-07-31T03:00:00Z");
    private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 31);

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private PlantPhotoRepository photoRepository;

    @Mock
    private RobotBusyGuard busyGuard;

    @Mock
    private DeviceCommandService commandService;

    private DailyCaptureScheduler scheduler(Instant now) {
        return scheduler(now, true);
    }

    private DailyCaptureScheduler scheduler(Instant now, boolean autoCaptureEnabled) {
        return new DailyCaptureScheduler(
                assignmentRepository,
                photoRepository,
                busyGuard,
                commandService,
                new DeviceCommandProperties(
                        120, 30, 30, true, true, 8, 17, 60, autoCaptureEnabled, 10, 16,
                        true, 600, 9000, 8, 22, 15, 6),
                new SensorQueryProperties("+09:00", 30, 7, 90),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    @Test
    void startsTheRunByNavigatingToTheStation() {
        // 카메라가 스테이션에 고정이라 로봇을 데려가지 않으면 빈 스테이션 사진이 남는다.
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);

        assertThat(scheduler(NOON_KST).captureOnce()).isEqualTo(1);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(
                eq(PLANT_ID), request.capture(), eq(CommandPurpose.CAPTURE));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.WATER_STATION);
    }

    @Test
    void arrivalTriggersCaptureThenReturnsHome() {
        DailyCaptureScheduler scheduler = scheduler(NOON_KST);

        scheduler.onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.CAPTURE,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.OK));
        scheduler.onCommandCompleted(completed(
                DeviceCommandType.CAPTURE, CommandPurpose.CAPTURE, null, DeviceCommandStatus.OK));

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService, times(2)).issueAuto(
                eq(PLANT_ID), request.capture(), eq(CommandPurpose.CAPTURE));
        assertThat(request.getAllValues().get(0).type()).isEqualTo(DeviceCommandType.CAPTURE);
        assertThat(request.getAllValues().get(1).type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getAllValues().get(1).destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void doesNotContinueOtherChains() {
        // 급수 체인의 스테이션 도착에 촬영을 붙이면 사용자가 시키지 않은 사진이 찍힌다.
        scheduler(NOON_KST).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.WATERING,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.OK));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void failureStopsTheRun() {
        scheduler(NOON_KST).onCommandCompleted(completed(
                DeviceCommandType.NAVIGATE, CommandPurpose.CAPTURE,
                RobotLocationType.WATER_STATION, DeviceCommandStatus.ERROR));

        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void usesTheServiceTimezoneForTodaysDate() {
        // UTC 로 끊으면 한국 시간 오전이 전날로 밀려 어제 사진이 있는지를 보게 된다.
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);

        scheduler(NOON_KST).captureOnce();

        verify(photoRepository).existsByPlantIdAndSourceAndPhotoDate(
                PLANT_ID, PhotoSource.DEVICE, TODAY_KST);
    }

    @Test
    void skipsWhenTodaysPhotoAlreadyExists() {
        // 하루 한 장이다. 주기 검사가 여러 번 돌아도 한 장만 남아야 한다.
        givenPlants(PLANT_ID);
        when(photoRepository.existsByPlantIdAndSourceAndPhotoDate(
                PLANT_ID, PhotoSource.DEVICE, TODAY_KST)).thenReturn(true);

        assertThat(scheduler(NOON_KST).captureOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void yieldsWhenAnotherChainIsUsingTheRobot() {
        // 급수나 말리기가 진행 중이다. 끝나면 다음 주기에 다시 본다.
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        when(busyGuard.isBusy(PLANT_ID)).thenReturn(true);

        assertThat(scheduler(NOON_KST).captureOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void oneFailingPlantDoesNotBlockTheOthers() {
        // 로봇 한 대가 고장 났을 때 전체 사진이 끊기면 안 된다.
        givenPlants(PLANT_ID, OTHER_PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        givenNoPhotoToday(OTHER_PLANT_ID);
        when(commandService.issueAuto(eq(PLANT_ID), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        assertThat(scheduler(NOON_KST).captureOnce()).isEqualTo(1);
        verify(commandService).issueAuto(eq(OTHER_PLANT_ID), any(), any());
    }

    @Test
    void doesNotCaptureOutsideTheDaylightWindow() {
        // 한국 시간 22:00. 어두운 사진은 쓸모없고 생장 단계 추론이 오탐을 낸다.
        scheduler(Instant.parse("2026-07-31T13:00:00Z")).capture();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void disabledFlagStopsCaptureEntirely() {
        scheduler(NOON_KST, false).capture();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
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

    private void givenPlants(String... plantIds) {
        List<AssignedDeviceView> views = java.util.Arrays.stream(plantIds)
                .map(plantId -> {
                    AssignedDeviceView view = mock(AssignedDeviceView.class);
                    lenient().when(view.getPlantId()).thenReturn(plantId);
                    return view;
                })
                .map(AssignedDeviceView.class::cast)
                .toList();
        when(assignmentRepository.findActiveAssignedDevices(
                IotDeviceType.RASPBERRY_PI, PlantStatus.DELETED)).thenReturn(views);
    }

    private void givenNoPhotoToday(String plantId) {
        lenient().when(photoRepository.existsByPlantIdAndSourceAndPhotoDate(
                plantId, PhotoSource.DEVICE, TODAY_KST)).thenReturn(false);
    }
}
