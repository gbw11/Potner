package com.potner.device.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
import com.potner.device.domain.Robot;
import com.potner.device.domain.RobotRepository;
import com.potner.device.dto.PlantAssignmentResponse;
import com.potner.device.dto.RegisterIotDeviceRequest;
import com.potner.device.dto.RegisterRobotRequest;
import com.potner.device.dto.RegisteredRobotResponse;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceRegistrationServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String DEVICE_UID = "raspberry-01";
    private static final Instant NOW = Instant.parse("2026-07-27T05:00:00Z");

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private RobotRepository robotRepository;

    @Mock
    private IotDeviceRepository iotDeviceRepository;

    @Mock
    private PlantDeviceAssignmentRepository assignmentRepository;

    private DeviceRegistrationService deviceRegistrationService;

    @BeforeEach
    void setUp() {
        deviceRegistrationService = new DeviceRegistrationService(
                appUserRepository,
                plantRepository,
                robotRepository,
                iotDeviceRepository,
                assignmentRepository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void registeredRobotHasNoDeviceOrAssignmentYet() {
        AppUser user = mock(AppUser.class);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(robotRepository.existsByDeviceUid(DEVICE_UID)).thenReturn(false);
        when(iotDeviceRepository.existsByDeviceUid(DEVICE_UID)).thenReturn(false);
        when(robotRepository.save(any(Robot.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RegisteredRobotResponse response = deviceRegistrationService.registerRobot(
                USER_ID,
                new RegisterRobotRequest("  " + DEVICE_UID + "  ", "  거실 로봇  ")
        );

        // 앞뒤 공백을 지운다. deviceUid 는 MQTT 토픽 세그먼트가 되므로 공백이 남으면 구독이 어긋난다.
        assertThat(response.deviceUid()).isEqualTo(DEVICE_UID);
        assertThat(response.name()).isEqualTo("거실 로봇");
        // 등록만으로는 측정값이 저장되지 않는다는 것이 응답에 드러나야 한다.
        assertThat(response.devices()).isEmpty();
        assertThat(response.assignedPlantId()).isNull();
        assertThat(response.connectionStatus().name()).isEqualTo("OFFLINE");
    }

    @Test
    void deviceUidTakenByAnotherRobotIsRejected() {
        AppUser user = mock(AppUser.class);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(robotRepository.existsByDeviceUid(DEVICE_UID)).thenReturn(true);

        assertBusinessError(
                () -> deviceRegistrationService.registerRobot(
                        USER_ID,
                        new RegisterRobotRequest(DEVICE_UID, "거실 로봇")),
                ErrorCode.DEVICE_UID_ALREADY_REGISTERED
        );
        verify(robotRepository, never()).save(any());
    }

    @Test
    void deviceUidTakenByIotDeviceIsAlsoRejected() {
        // device_uid 는 robot 과 iot_device 를 통틀어 유일해야 한다.
        // MQTT 토픽 세그먼트로 쓰이므로 겹치면 어느 장치의 측정값인지 가릴 수 없다.
        AppUser user = mock(AppUser.class);
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(robotRepository.existsByDeviceUid(DEVICE_UID)).thenReturn(false);
        when(iotDeviceRepository.existsByDeviceUid(DEVICE_UID)).thenReturn(true);

        assertBusinessError(
                () -> deviceRegistrationService.registerRobot(
                        USER_ID,
                        new RegisterRobotRequest(DEVICE_UID, "거실 로봇")),
                ErrorCode.DEVICE_UID_ALREADY_REGISTERED
        );
        verify(robotRepository, never()).save(any());
    }

    @Test
    void anotherUsersRobotIsNotFoundWhenRegisteringIotDevice() {
        when(robotRepository.findByIdAndUserId(ROBOT_ID, USER_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> deviceRegistrationService.registerIotDevice(
                        USER_ID,
                        ROBOT_ID,
                        new RegisterIotDeviceRequest("jetson-01", IotDeviceType.JETSON_ORIN)),
                ErrorCode.ROBOT_NOT_FOUND
        );
        verify(iotDeviceRepository, never()).save(any());
    }

    @Test
    void assignmentLinksPlantAndRobot() {
        givenOwnedPlant();
        Robot robot = givenOwnedRobot();
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.empty());
        when(assignmentRepository.save(any(PlantDeviceAssignment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PlantAssignmentResponse response =
                deviceRegistrationService.assignRobot(USER_ID, PLANT_ID, ROBOT_ID);

        assertThat(response.plantId()).isEqualTo(PLANT_ID);
        assertThat(response.robotDeviceUid()).isEqualTo(DEVICE_UID);
        // 저장 시각은 UTC 다. Clock 을 주입받으므로 JVM 타임존에 흔들리지 않는다.
        assertThat(response.assignedAt()).isEqualTo(LocalDateTime.of(2026, 7, 27, 5, 0));
    }

    @Test
    void plantThatAlreadyHasRobotIsRejected() {
        givenOwnedPlant();
        givenOwnedRobot();
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(mock(PlantDeviceAssignment.class)));

        assertBusinessError(
                () -> deviceRegistrationService.assignRobot(USER_ID, PLANT_ID, ROBOT_ID),
                ErrorCode.PLANT_ALREADY_ASSIGNED
        );
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void robotThatAlreadyServesAnotherPlantIsRejected() {
        givenOwnedPlant();
        givenOwnedRobot();
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByRobotIdAndUnassignedAtIsNullOrderByAssignedAtDesc(ROBOT_ID))
                .thenReturn(Optional.of(mock(PlantDeviceAssignment.class)));

        assertBusinessError(
                () -> deviceRegistrationService.assignRobot(USER_ID, PLANT_ID, ROBOT_ID),
                ErrorCode.ROBOT_ALREADY_ASSIGNED
        );
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void unassignKeepsRowAndOnlyStampsTime() {
        givenOwnedPlant();
        PlantDeviceAssignment assignment =
                PlantDeviceAssignment.assign(PLANT_ID, ROBOT_ID, LocalDateTime.of(2026, 7, 20, 0, 0));
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.of(assignment));

        deviceRegistrationService.unassignRobot(USER_ID, PLANT_ID);

        // 행을 지우지 않는다. 파생 키가 NULL 이 되어 같은 식물에 재배정이 가능해진다.
        assertThat(assignment.getUnassignedAt()).isEqualTo(LocalDateTime.of(2026, 7, 27, 5, 0));
        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void unassignWithoutActiveAssignmentIsNotFound() {
        givenOwnedPlant();
        when(assignmentRepository.findFirstByPlantIdAndUnassignedAtIsNullOrderByAssignedAtDesc(PLANT_ID))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> deviceRegistrationService.unassignRobot(USER_ID, PLANT_ID),
                ErrorCode.PLANT_ASSIGNMENT_NOT_FOUND
        );
    }

    @Test
    void anotherUsersPlantIsNotFoundWhenAssigning() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> deviceRegistrationService.assignRobot(USER_ID, PLANT_ID, ROBOT_ID),
                ErrorCode.PLANT_NOT_FOUND
        );
        verify(assignmentRepository, never()).save(any());
    }

    @Test
    void robotListIsEmptyWithoutExtraQueriesWhenNothingRegistered() {
        when(robotRepository.findAllByUserIdOrderByNameAsc(USER_ID)).thenReturn(List.of());

        assertThat(deviceRegistrationService.getRobots(USER_ID).robots()).isEmpty();
        verify(iotDeviceRepository, never()).findAllByRobotIdOrderByDeviceTypeAsc(any());
    }

    private void givenOwnedPlant() {
        Plant plant = mock(Plant.class);
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
    }

    private Robot givenOwnedRobot() {
        Robot robot = mock(Robot.class);
        org.mockito.Mockito.lenient().when(robot.getId()).thenReturn(ROBOT_ID);
        org.mockito.Mockito.lenient().when(robot.getDeviceUid()).thenReturn(DEVICE_UID);
        when(robotRepository.findByIdAndUserId(ROBOT_ID, USER_ID)).thenReturn(Optional.of(robot));
        return robot;
    }

    private void assertBusinessError(Runnable invocation, ErrorCode expectedErrorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedErrorCode));
    }
}
