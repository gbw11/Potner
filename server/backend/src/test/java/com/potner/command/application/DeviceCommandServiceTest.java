package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.location.domain.RobotLocation;
import com.potner.location.domain.RobotLocationRepository;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");
    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String PI_UID = "raspberry-01";
    private static final String JETSON_UID = "jetson-01";

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private RobotLocationRepository locationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DeviceCommandService service;

    @BeforeEach
    void setUp() {
        service = new DeviceCommandService(
                commandRepository,
                plantRepository,
                assignmentRepository,
                iotDeviceRepository,
                profileRepository,
                locationRepository,
                new SensorQueryProperties("+09:00", 15, 14, 365),
                new DeviceCommandProperties(120, 30, 30, true, true, 8, 17, 60, true, 10, 16, true, 600, 9000, 8, 22, 15, 6),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        givenOwnedPlant();
        lenient().when(commandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void issuesAWaterCommandWithTheProfileAmount() {
        givenDevices();
        givenProfileWateringMl(new BigDecimal("350.00"));

        DeviceCommandResponse response = service.issue(USER_ID, PLANT_ID, water());

        assertThat(response.status()).isEqualTo(DeviceCommandStatus.ISSUED);
        assertThat(response.requestedMl()).isEqualByComparingTo("350.00");

        ArgumentCaptor<DeviceCommandIssuedEvent> event =
                ArgumentCaptor.forClass(DeviceCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().deviceUid()).isEqualTo(PI_UID);
        assertThat(event.getValue().commandType()).isEqualTo(DeviceCommandType.WATER);
    }

    @Test
    void navigateGoesToTheJetsonWithTheStoredPose() {
        // 이동은 바퀴가 달린 젯슨이 받는다. 좌표는 robot_location 이 유일한 출처다.
        givenDevices();
        RobotLocation sunlight = RobotLocation.register(ROBOT_ID, RobotLocationType.SUNLIGHT, null);
        sunlight.updatePose(
                new BigDecimal("1.250"), new BigDecimal("-0.480"), new BigDecimal("1.5708"));
        when(locationRepository.findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.SUNLIGHT))
                .thenReturn(Optional.of(sunlight));

        DeviceCommandResponse response = service.issue(
                USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(
                        DeviceCommandType.NAVIGATE, RobotLocationType.SUNLIGHT, null));

        assertThat(response.destination()).isEqualTo(RobotLocationType.SUNLIGHT);

        ArgumentCaptor<DeviceCommandIssuedEvent> event =
                ArgumentCaptor.forClass(DeviceCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().deviceUid()).isEqualTo(JETSON_UID);
        assertThat(event.getValue().poseX()).isEqualByComparingTo("1.250");
        assertThat(event.getValue().poseYaw()).isEqualByComparingTo("1.5708");
    }

    @Test
    void mappingCommandsGoToTheJetsonWithoutADestination() {
        // 지도 제작은 목적지도 좌표도 없다 — 지도를 그리려고 하는 일이라 좌표가 있을 수 없다.
        // 받는 쪽은 바퀴가 달린 젯슨이고, 무엇을 할지는 토픽 이름이 정한다.
        for (DeviceCommandType type : List.of(
                DeviceCommandType.MAPPING_START,
                DeviceCommandType.MAPPING_SAVE,
                DeviceCommandType.MAPPING_CANCEL)) {
            reset(eventPublisher);
            givenDevices();
            when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                    PLANT_ID, type, DeviceCommandStatus.ISSUED)).thenReturn(false);

            DeviceCommandResponse response = service.issue(
                    USER_ID, PLANT_ID, new IssueDeviceCommandRequest(type, null, null));

            assertThat(response.destination()).isNull();

            ArgumentCaptor<DeviceCommandIssuedEvent> event =
                    ArgumentCaptor.forClass(DeviceCommandIssuedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().deviceUid()).isEqualTo(JETSON_UID);
            assertThat(event.getValue().destination()).isNull();
            assertThat(event.getValue().runSeconds()).isNull();
            assertThat(event.getValue().requestedMl()).isNull();
        }
    }

    @Test
    void mappingCommandNamesMatchTheTopicSuffix() {
        // 토픽 마지막 조각이자 결과 토픽의 짝이다. 젯슨 구독 이름과 어긋나면 명령이 조용히 사라진다.
        assertThat(DeviceCommandType.MAPPING_START.commandName()).isEqualTo("mapping-start");
        assertThat(DeviceCommandType.MAPPING_SAVE.commandName()).isEqualTo("mapping-save");
        assertThat(DeviceCommandType.MAPPING_CANCEL.commandName()).isEqualTo("mapping-cancel");
    }

    @Test
    void navigateWithoutAConfiguredPoseIsRejected() {
        // 좌표 없는 위치로 보내면 로봇이 갈 곳을 모른다. 0,0,0 으로 채우면 지도 원점으로 달려간다.
        givenDevices();
        RobotLocation home = RobotLocation.register(ROBOT_ID, RobotLocationType.HOME, null);
        when(locationRepository.findByRobotIdAndLocationType(ROBOT_ID, RobotLocationType.HOME))
                .thenReturn(Optional.of(home));

        assertThatThrownBy(() -> service.issue(
                USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(
                        DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.LOCATION_POSE_NOT_CONFIGURED);
        verify(commandRepository, never()).save(any());
    }

    @Test
    void navigateRequiresADestination() {
        assertThatThrownBy(() -> service.issue(
                USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(DeviceCommandType.NAVIGATE, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NAVIGATE_DESTINATION_REQUIRED);
    }

    @Test
    void fanUsesTheDefaultSecondsWhenOmitted() {
        givenDevices();

        service.issue(USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(DeviceCommandType.FAN, null, null));

        ArgumentCaptor<DeviceCommandIssuedEvent> event =
                ArgumentCaptor.forClass(DeviceCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().deviceUid()).isEqualTo(PI_UID);
        assertThat(event.getValue().runSeconds()).isEqualTo(30);
    }

    @Test
    void rejectsFieldsThatDoNotMatchTheType() {
        // 무시하고 진행하면 앱이 잘못 조립한 요청이 조용히 통과해 버그가 늦게 드러난다.
        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(
                        DeviceCommandType.WATER, RobotLocationType.HOME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(DeviceCommandType.WATER, null, 30)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void rejectsWhenACommandOfTheSameTypeIsStillPending() {
        when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                PLANT_ID, DeviceCommandType.WATER, DeviceCommandStatus.ISSUED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID, water()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEVICE_COMMAND_ALREADY_PENDING);
        verify(commandRepository, never()).save(any());
    }

    @Test
    void rejectsWhenTheRobotLacksTheTargetDevice() {
        // 이동은 젯슨이 받는다. 라즈베리만 등록된 로봇에는 보낼 곳이 없다.
        givenAssignment();
        IotDevice raspberry = mock(IotDevice.class);
        when(raspberry.getDeviceType()).thenReturn(IotDeviceType.RASPBERRY_PI);
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(raspberry));

        assertThatThrownBy(() -> service.issue(
                USER_ID, PLANT_ID,
                new IssueDeviceCommandRequest(
                        DeviceCommandType.NAVIGATE, RobotLocationType.HOME, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DEVICE_NOT_FOUND);
    }

    @Test
    void rejectsWateringWhenTheProfileHasNoAmount() {
        givenDevices();
        givenProfileWateringMl(null);

        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID, water()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATERING_AMOUNT_NOT_CONFIGURED);
        verify(commandRepository, never()).save(any());
    }

    @Test
    void issuedRowRecordsTheTargetDevice() {
        givenDevices();
        givenProfileWateringMl(new BigDecimal("100"));

        service.issue(USER_ID, PLANT_ID, water());

        ArgumentCaptor<DeviceCommand> saved = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(commandRepository).save(saved.capture());
        assertThat(saved.getValue().getPlantId()).isEqualTo(PLANT_ID);
        assertThat(saved.getValue().getRobotId()).isEqualTo(ROBOT_ID);
        assertThat(saved.getValue().getDeviceUid()).isEqualTo(PI_UID);
        assertThat(saved.getValue().getIssuedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private IssueDeviceCommandRequest water() {
        return new IssueDeviceCommandRequest(DeviceCommandType.WATER, null, null);
    }

    private void givenOwnedPlant() {
        Plant plant = mock(Plant.class);
        lenient().when(plantRepository
                        .findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
    }

    private void givenAssignment() {
        PlantDeviceAssignment assignment = mock(PlantDeviceAssignment.class);
        when(assignment.getRobotId()).thenReturn(ROBOT_ID);
        when(assignmentRepository
                .findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(assignment));
    }

    /** 라즈베리와 젯슨이 모두 등록된 로봇이다. 명령 종류가 받을 장치를 고른다. */
    private void givenDevices() {
        givenAssignment();
        IotDevice raspberry = mock(IotDevice.class);
        lenient().when(raspberry.getDeviceType()).thenReturn(IotDeviceType.RASPBERRY_PI);
        lenient().when(raspberry.getDeviceUid()).thenReturn(PI_UID);
        IotDevice jetson = mock(IotDevice.class);
        lenient().when(jetson.getDeviceType()).thenReturn(IotDeviceType.JETSON_ORIN);
        lenient().when(jetson.getDeviceUid()).thenReturn(JETSON_UID);
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(jetson, raspberry));
    }

    private void givenProfileWateringMl(BigDecimal amount) {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getRecommendedWateringMl()).thenReturn(amount);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }
}
