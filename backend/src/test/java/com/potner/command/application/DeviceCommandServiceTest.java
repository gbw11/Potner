package com.potner.command.application;

import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.device.domain.IotDevice;
import com.potner.device.domain.IotDeviceRepository;
import com.potner.device.domain.IotDeviceType;
import com.potner.device.domain.PlantDeviceAssignment;
import com.potner.device.domain.PlantDeviceAssignmentRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T06:00:00Z");
    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";
    private static final String ROBOT_ID = "30000000-0000-0000-0000-0000000000cc";
    private static final String PI_UID = "raspberry-01";

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
                new SensorQueryProperties("+09:00", 15, 14, 365),
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        givenOwnedPlant();
        lenient().when(commandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void issuesAWaterCommandWithTheProfileAmount() {
        givenAssignmentWithRaspberry();
        givenProfileWateringMl(new BigDecimal("350.00"));

        DeviceCommandResponse response = service.issue(USER_ID, PLANT_ID, DeviceCommandType.WATER);

        assertThat(response.status()).isEqualTo(DeviceCommandStatus.ISSUED);
        assertThat(response.requestedMl()).isEqualByComparingTo("350.00");
        assertThat(response.requestId()).isNotBlank();

        // 발행 이벤트는 커밋 뒤 MQTT 로 나간다. 여기서는 값만 확인한다.
        ArgumentCaptor<DeviceCommandIssuedEvent> event =
                ArgumentCaptor.forClass(DeviceCommandIssuedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().deviceUid()).isEqualTo(PI_UID);
        assertThat(event.getValue().commandType()).isEqualTo(DeviceCommandType.WATER);
        assertThat(event.getValue().requestedMl()).isEqualByComparingTo("350.00");
        assertThat(event.getValue().requestId()).isEqualTo(response.requestId());
    }

    @Test
    void issuesACaptureCommandWithoutAnAmount() {
        givenAssignmentWithRaspberry();

        DeviceCommandResponse response = service.issue(USER_ID, PLANT_ID, DeviceCommandType.CAPTURE);

        assertThat(response.requestedMl()).isNull();
        // 촬영은 생육 기준을 보지 않는다. 기준이 없어도 촬영은 돼야 한다.
        verify(profileRepository, never()).findByPlantId(any());
    }

    @Test
    void rejectsWhenACommandOfTheSameTypeIsStillPending() {
        // 장치도 BUSY 로 거절하지만, 서버에서 먼저 막아야 사용자가 즉시 이유를 본다.
        when(commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                PLANT_ID, DeviceCommandType.WATER, DeviceCommandStatus.ISSUED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID, DeviceCommandType.WATER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.DEVICE_COMMAND_ALREADY_PENDING);
        verify(commandRepository, never()).save(any());
    }

    @Test
    void rejectsWhenTheRobotHasNoRaspberry() {
        // 펌프와 카메라는 라즈베리에 달려 있다. 젯슨만 등록된 로봇에는 보낼 곳이 없다.
        givenAssignment();
        IotDevice jetson = mock(IotDevice.class);
        when(jetson.getDeviceType()).thenReturn(IotDeviceType.JETSON_ORIN);
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(jetson));

        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID, DeviceCommandType.WATER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMMAND_DEVICE_NOT_FOUND);
    }

    @Test
    void rejectsWateringWhenTheProfileHasNoAmount() {
        // 기본값으로 아무 양이나 보내면 화분 크기에 따라 과급수가 된다. 설정을 요구한다.
        givenAssignmentWithRaspberry();
        givenProfileWateringMl(null);

        assertThatThrownBy(() -> service.issue(USER_ID, PLANT_ID, DeviceCommandType.WATER))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.WATERING_AMOUNT_NOT_CONFIGURED);
        verify(commandRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(DeviceCommandIssuedEvent.class));
    }

    @Test
    void issuedRowRecordsTheTargetDevice() {
        givenAssignmentWithRaspberry();
        givenProfileWateringMl(new BigDecimal("100"));

        service.issue(USER_ID, PLANT_ID, DeviceCommandType.WATER);

        ArgumentCaptor<DeviceCommand> saved = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(commandRepository).save(saved.capture());
        assertThat(saved.getValue().getPlantId()).isEqualTo(PLANT_ID);
        assertThat(saved.getValue().getRobotId()).isEqualTo(ROBOT_ID);
        assertThat(saved.getValue().getDeviceUid()).isEqualTo(PI_UID);
        assertThat(saved.getValue().getIssuedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
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

    private void givenAssignmentWithRaspberry() {
        givenAssignment();
        IotDevice raspberry = mock(IotDevice.class);
        when(raspberry.getDeviceType()).thenReturn(IotDeviceType.RASPBERRY_PI);
        // 급수량이 비어 발행 전에 실패하는 테스트는 uid 를 읽지 않는다.
        lenient().when(raspberry.getDeviceUid()).thenReturn(PI_UID);
        when(iotDeviceRepository.findAllByRobotIdOrderByDeviceTypeAsc(ROBOT_ID))
                .thenReturn(List.of(raspberry));
    }

    private void givenProfileWateringMl(BigDecimal amount) {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        when(profile.getRecommendedWateringMl()).thenReturn(amount);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }
}
