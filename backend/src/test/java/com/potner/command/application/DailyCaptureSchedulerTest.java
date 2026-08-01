package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.AssignedDeviceView;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCaptureSchedulerTest {

    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String OTHER_PLANT_ID = "20000000-0000-0000-0000-0000000000cc";

    /** 한국 시간 12:00 — 촬영 창(10~16시) 안이다. */
    private static final Instant NOON_KST = Instant.parse("2026-07-31T03:00:00Z");
    private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 31);

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private PlantPhotoRepository photoRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private DeviceCommandService commandService;

    private DailyCaptureScheduler scheduler(Instant now) {
        return new DailyCaptureScheduler(
                assignmentRepository,
                photoRepository,
                commandRepository,
                commandService,
                new DeviceCommandProperties(120, 30, 30, true, true, 8, 17, 60, true, 10, 16),
                new SensorQueryProperties("+09:00", 30, 7, 90),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    @Test
    void issuesCaptureWhenTodayHasNoPhotoYet() {
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        givenNoPendingCommands(PLANT_ID);

        assertThat(scheduler(NOON_KST).captureOnce()).isEqualTo(1);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture());
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.CAPTURE);
        assertThat(request.getValue().destination()).isNull();
    }

    @Test
    void usesTheServiceTimezoneForTodaysDate() {
        // UTC 로 끊으면 한국 시간 오전이 전날로 밀려 어제 사진이 있는지를 보게 된다.
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        givenNoPendingCommands(PLANT_ID);

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
        verify(commandService, never()).issueAuto(anyString(), any());
    }

    @Test
    void skipsWhileACaptureIsAwaitingItsResult() {
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                PLANT_ID, DeviceCommandType.CAPTURE, DeviceCommandStatus.ISSUED)).thenReturn(true);

        assertThat(scheduler(NOON_KST).captureOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any());
    }

    @Test
    void skipsWhileTheRobotIsMoving() {
        // 자동 급수나 햇빛 재배치로 이동 중이면 흔들리거나 엉뚱한 각도가 나온다.
        givenPlants(PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                PLANT_ID, DeviceCommandType.CAPTURE, DeviceCommandStatus.ISSUED)).thenReturn(false);
        when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                PLANT_ID, DeviceCommandType.NAVIGATE, DeviceCommandStatus.ISSUED)).thenReturn(true);

        assertThat(scheduler(NOON_KST).captureOnce()).isZero();
        verify(commandService, never()).issueAuto(anyString(), any());
    }

    @Test
    void oneFailingPlantDoesNotBlockTheOthers() {
        // 한 식물의 장치 문제가 다른 식물의 촬영을 막으면, 로봇 한 대가 고장 났을 때 전체
        // 사진이 끊긴다.
        givenPlants(PLANT_ID, OTHER_PLANT_ID);
        givenNoPhotoToday(PLANT_ID);
        givenNoPhotoToday(OTHER_PLANT_ID);
        givenNoPendingCommands(PLANT_ID);
        givenNoPendingCommands(OTHER_PLANT_ID);
        when(commandService.issueAuto(eq(PLANT_ID), any()))
                .thenThrow(new BusinessException(ErrorCode.COMMAND_DEVICE_NOT_FOUND));

        assertThat(scheduler(NOON_KST).captureOnce()).isEqualTo(1);
        verify(commandService).issueAuto(eq(OTHER_PLANT_ID), any());
    }

    @Test
    void doesNotCaptureOutsideTheDaylightWindow() {
        // 한국 시간 22:00. 어두운 사진은 쓸모없고 생장 단계 추론이 오탐을 낸다.
        // 창 밖에서는 대상 조회조차 하지 않는다.
        scheduler(Instant.parse("2026-07-31T13:00:00Z")).capture();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
        verify(commandService, never()).issueAuto(anyString(), any());
    }

    @Test
    void disabledFlagStopsCaptureEntirely() {
        DailyCaptureScheduler disabled = new DailyCaptureScheduler(
                assignmentRepository,
                photoRepository,
                commandRepository,
                commandService,
                new DeviceCommandProperties(120, 30, 30, true, true, 8, 17, 60, false, 10, 16),
                new SensorQueryProperties("+09:00", 30, 7, 90),
                Clock.fixed(NOON_KST, ZoneOffset.UTC)
        );

        disabled.capture();

        verify(assignmentRepository, never()).findActiveAssignedDevices(any(), any());
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

    private void givenNoPendingCommands(String plantId) {
        lenient().when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                eq(plantId), any(), eq(DeviceCommandStatus.ISSUED))).thenReturn(false);
    }
}
