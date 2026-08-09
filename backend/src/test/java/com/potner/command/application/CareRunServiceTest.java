package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommand;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CareRunServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private DeviceCommandRepository commandRepository;

    @Mock
    private RobotBusyGuard busyGuard;

    @Mock
    private DeviceCommandService commandService;

    private CareRunService service(boolean allEnabled) {
        return new CareRunService(
                plantRepository,
                profileRepository,
                commandRepository,
                busyGuard,
                commandService,
                new DeviceCommandProperties(
                        120, 30, 30, allEnabled, allEnabled, 8, 17, 60, allEnabled, 10, 16,
                        allEnabled, 600, 9000, 8, 22, 15, 6)
        );
    }

    @Test
    void wateringStartsByNavigatingToTheStation() {
        // 시작 조건만 대신 만들고 순서는 서버가 정한다. 첫 단계는 평소와 같은 스테이션 이동이다.
        givenOwnedPlant();
        givenWateringAmount(new BigDecimal("150.00"));
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.WATERING);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(
                eq(PLANT_ID), request.capture(), eq(CommandPurpose.WATERING));
        assertThat(request.getValue().type()).isEqualTo(DeviceCommandType.NAVIGATE);
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.WATER_STATION);
    }

    @Test
    void relocationSendsTheRobotOutWhenItIsNotAtTheSunlightSpot() {
        givenOwnedPlant();
        givenLastNavigate(RobotLocationType.HOME);
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.RELOCATION);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(
                eq(PLANT_ID), request.capture(), eq(CommandPurpose.RELOCATION));
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.SUNLIGHT);
    }

    @Test
    void relocationBringsTheRobotBackWhenItIsAlreadyAtTheSunlightSpot() {
        // 재배치는 이어질 단계가 없는 단발 이동이라 체인이 복귀시켜 주지 않는다. 같은 요청으로
        // 되돌릴 수 없으면 목표 광량을 채우거나 해가 질 때까지 햇빛 자리에 서 있게 된다.
        givenOwnedPlant();
        givenLastNavigate(RobotLocationType.SUNLIGHT);
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.RELOCATION);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(
                eq(PLANT_ID), request.capture(), eq(CommandPurpose.RELOCATION));
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.HOME);
    }

    @Test
    void relocationGoesOutWhenThereIsNoMoveHistory() {
        // 어디 있는지 모르면 나가는 쪽으로 본다. 광량이 미달일 때 보내는 쪽이 안전하다.
        givenOwnedPlant();
        givenLastNavigate(null);
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.RELOCATION);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService).issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getValue().destination()).isEqualTo(RobotLocationType.SUNLIGHT);
    }

    @Test
    void relocationIgnoresTheBackgroundSchedulerFlag() {
        // 스케줄러만 끄고 이 요청은 쓰고 싶은 경우가 있다. 30분마다 "광량 미달이니 다시 나가라"
        // 고 판단하면 되돌린 로봇이 곧 다시 나가기 때문이다. 이어질 체인이 없어 막을 이유도 없다.
        givenOwnedPlant();
        givenLastNavigate(RobotLocationType.HOME);
        givenIssueSucceeds();

        service(false).start(USER_ID, PLANT_ID, CommandPurpose.RELOCATION);

        verify(commandService).issueAuto(anyString(), any(), eq(CommandPurpose.RELOCATION));
    }

    @Test
    void ventilationAndCaptureAlsoStartAtTheStation() {
        // 팬과 카메라가 스테이션에 고정이라 셋 다 이동이 앞선다.
        givenOwnedPlant();
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.DRYING);
        service(true).start(USER_ID, PLANT_ID, CommandPurpose.CAPTURE);

        ArgumentCaptor<IssueDeviceCommandRequest> request =
                ArgumentCaptor.forClass(IssueDeviceCommandRequest.class);
        verify(commandService, org.mockito.Mockito.times(2))
                .issueAuto(eq(PLANT_ID), request.capture(), any());
        assertThat(request.getAllValues())
                .allMatch(r -> r.destination() == RobotLocationType.WATER_STATION);
    }

    @Test
    void issuesAsAutoSoTheChainContinues() {
        // issueAuto 는 initiator 를 AUTO 로 남긴다. USER 로 발행하면 체인을 잇는 리스너들이
        // 회신을 무시해 로봇이 첫 이동만 하고 멈춘다.
        givenOwnedPlant();
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.CAPTURE);

        verify(commandService).issueAuto(anyString(), any(), eq(CommandPurpose.CAPTURE));
    }

    @Test
    void refusesWhenThatCareIsDisabled() {
        // 첫 이동만 나가고 체인을 이을 리스너가 회신을 무시하면 로봇이 스테이션에 남는다.
        givenOwnedPlant();

        assertThatThrownBy(() -> service(false).start(USER_ID, PLANT_ID, CommandPurpose.WATERING))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CARE_RUN_DISABLED);
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void refusesWhileTheRobotIsBusy() {
        // 급수 중에 재배치를 시작하면 펌프가 물을 내보내는 중에 바퀴가 움직인다.
        givenOwnedPlant();
        when(busyGuard.isBusy(PLANT_ID)).thenReturn(true);

        assertThatThrownBy(() -> service(true).start(USER_ID, PLANT_ID, CommandPurpose.DRYING))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CARE_RUN_ROBOT_BUSY);
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void refusesWateringBeforeMovingWhenTheAmountIsNotConfigured() {
        // 이동 명령은 급수량 없이도 발행된다. 여기서 안 보면 로봇이 스테이션까지 간 뒤에야
        // 급수 단계가 실패한다.
        givenOwnedPlant();
        givenWateringAmount(null);

        assertThatThrownBy(() -> service(true).start(USER_ID, PLANT_ID, CommandPurpose.WATERING))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.WATERING_AMOUNT_NOT_CONFIGURED);
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    @Test
    void doesNotCheckTheWateringAmountForOtherCares() {
        // 송풍·촬영·재배치는 급수량과 무관하다. 프로필을 보지 않는다.
        givenOwnedPlant();
        givenIssueSucceeds();

        service(true).start(USER_ID, PLANT_ID, CommandPurpose.DRYING);

        verify(profileRepository, never()).findByPlantId(anyString());
    }

    @Test
    void otherPeoplesPlantIsNotFound() {
        // 존재 여부를 숨기려고 403 이 아니라 404 다.
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(true).start(USER_ID, PLANT_ID, CommandPurpose.WATERING))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PLANT_NOT_FOUND);
        verify(commandService, never()).issueAuto(anyString(), any(), any());
    }

    private void givenOwnedPlant() {
        lenient().when(plantRepository.findByIdAndUserIdAndStatusNot(
                        PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(mock(Plant.class)));
    }

    private void givenWateringAmount(BigDecimal amount) {
        PlantGrowthProfile profile = mock(PlantGrowthProfile.class);
        lenient().when(profile.getRecommendedWateringMl()).thenReturn(amount);
        lenient().when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
    }

    private void givenLastNavigate(RobotLocationType destination) {
        DeviceCommand command = null;
        if (destination != null) {
            command = mock(DeviceCommand.class);
            lenient().when(command.getDestination()).thenReturn(destination);
        }
        lenient().when(commandRepository
                        .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                                PLANT_ID, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK))
                .thenReturn(Optional.ofNullable(command));
    }

    private void givenIssueSucceeds() {
        lenient().when(commandService.issueAuto(anyString(), any(), any()))
                .thenReturn(mock(DeviceCommandResponse.class));
    }
}
