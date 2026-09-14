package com.potner.command.application;

import com.potner.command.config.DeviceCommandProperties;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.command.dto.DeviceCommandResponse;
import com.potner.command.dto.IssueDeviceCommandRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.location.domain.RobotLocationType;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 자동 케어 한 회차를 지금 시작한다.
 *
 * <p>자동 케어는 조건이 갖춰져야 돈다 — 급수는 토양수분 부족 알림, 환기는 마지막 송풍 이후의
 * 간격, 재배치는 하루 누적 광량의 미달, 촬영은 그날 사진의 부재다. 시연 자리에서는 그 조건을
 * 만들 수 없거나 다음 검사 주기까지 기다릴 수 없다.
 *
 * <p><strong>건너뛰는 것은 "언제 시작할지" 하나뿐이다.</strong> 첫 명령을 발행한 뒤로는 손대지
 * 않으므로 이동·작업·복귀와 체인 연결, 회신 대기, 타임아웃이 모두 평소 경로 그대로다. 앱이
 * 명령 종류를 고르지 않는 이유도 같다 — 순서를 정하는 것은 서버의 몫으로 남겨야 자동 케어와
 * 시연 경로가 두 벌로 갈리지 않는다.
 *
 * <p>{@code initiator} 는 {@code AUTO} 다. 체인을 잇는 리스너들이 사용자 명령의 회신에는
 * 후속을 붙이지 않기 때문이고(그래야 사용자가 시키지 않은 동작을 보지 않는다), 시작 신호만
 * 사람이 줬을 뿐 이후 판단은 실제로 서버가 한다. 대신 이력에서 이 회차를 스케줄러가 스스로
 * 시작한 것과 구분할 수 없다 — 구분이 필요해지면 {@code initiator} 에 값을 하나 더 두어야
 * 한다.
 */
@Service
public class CareRunService {

    private static final Logger log = LoggerFactory.getLogger(CareRunService.class);

    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final DeviceCommandRepository commandRepository;
    private final RobotBusyGuard busyGuard;
    private final DeviceCommandService commandService;
    private final DeviceCommandProperties properties;

    public CareRunService(
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            DeviceCommandRepository commandRepository,
            RobotBusyGuard busyGuard,
            DeviceCommandService commandService,
            DeviceCommandProperties properties
    ) {
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.commandRepository = commandRepository;
        this.busyGuard = busyGuard;
        this.commandService = commandService;
        this.properties = properties;
    }

    public DeviceCommandResponse start(String userId, String plantId, CommandPurpose purpose) {
        requireOwnedPlant(userId, plantId);
        requireEnabled(purpose);
        requireIdleRobot(plantId);
        if (purpose == CommandPurpose.WATERING) {
            requireWateringAmount(plantId);
        }

        DeviceCommandResponse issued = commandService.issueAuto(
                plantId,
                new IssueDeviceCommandRequest(
                        DeviceCommandType.NAVIGATE, firstDestination(plantId, purpose), null),
                purpose
        );
        log.info(
                "Care run started by request: plantId={}, purpose={}, requestId={}",
                plantId, purpose, issued.requestId()
        );
        return issued;
    }

    /**
     * 첫 단계의 목적지다. 넷 다 이동으로 시작한다.
     *
     * <p>펌프·팬·카메라가 스테이션에 고정이라 급수·환기·촬영은 그리로 가야 한다.
     *
     * <p><strong>재배치만 방향을 뒤집는다.</strong> 나머지 셋은 작업을 마치면 체인이 로봇을
     * 대기 장소로 돌려보내지만, 재배치는 이어질 단계가 없는 단발 이동이다. 햇빛 자리는 가서
     * 일하고 오는 곳이 아니라 머무는 곳이고, 복귀는 목표 광량을 채우거나 해가 진 뒤에
     * {@link SunlightRelocationScheduler} 가 판단한다.
     *
     * <p>그 판단을 기다릴 수 없을 때 같은 요청으로 되돌릴 수 있어야 한다. 지금 햇빛 자리에
     * 있으면 대기 장소를, 아니면 햇빛 자리를 목적지로 준다.
     */
    private RobotLocationType firstDestination(String plantId, CommandPurpose purpose) {
        return switch (purpose) {
            case WATERING, DRYING, CAPTURE -> RobotLocationType.WATER_STATION;
            case RELOCATION -> isAtSunlight(plantId)
                    ? RobotLocationType.HOME
                    : RobotLocationType.SUNLIGHT;
        };
    }

    /**
     * 로봇이 햇빛 자리에 서 있는지 본다. 마지막으로 성공한 이동의 목적지로 추정한다 —
     * 자동 재배치·환기가 쓰는 것과 같은 방식이다.
     */
    private boolean isAtSunlight(String plantId) {
        return commandRepository
                .findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
                        plantId, DeviceCommandType.NAVIGATE, DeviceCommandStatus.OK)
                .map(command -> command.getDestination() == RobotLocationType.SUNLIGHT)
                .orElse(false);
    }

    /**
     * 해당 자동 케어가 켜져 있는지 본다.
     *
     * <p>꺼진 케어를 시작하면 첫 이동만 나가고 체인을 이을 리스너가 회신을 무시해 로봇이
     * 스테이션에 서 있게 된다. 시작 자체를 막아 그 상태를 만들지 않는다.
     *
     * <p><strong>재배치는 플래그를 보지 않는다.</strong> 이어질 체인이 없어 중간에 멈출 것이
     * 없고, 오히려 배경 스케줄러만 끈 채로 이 요청은 쓰고 싶은 경우가 있다 — 스케줄러가
     * 30분마다 "광량 미달이니 다시 나가라" 고 판단하면 되돌린 로봇이 곧 다시 나간다.
     */
    private void requireEnabled(CommandPurpose purpose) {
        boolean enabled = switch (purpose) {
            case WATERING -> properties.autoWaterEnabled();
            case DRYING -> properties.autoDryingEnabled();
            case CAPTURE -> properties.autoCaptureEnabled();
            case RELOCATION -> true;
        };
        if (!enabled) {
            throw new BusinessException(ErrorCode.CARE_RUN_DISABLED);
        }
    }

    /**
     * 로봇이 다른 일을 하고 있으면 시작하지 않는다.
     *
     * <p>{@code issueAuto} 도 같은 종류의 대기 명령을 막지만 종류가 다르면 통과한다. 급수 중에
     * 재배치를 시작하면 펌프가 물을 내보내는 중에 바퀴가 움직인다 — 스케줄러들이 쓰는 것과 같은
     * 검사를 여기서도 한다.
     */
    private void requireIdleRobot(String plantId) {
        if (busyGuard.isBusy(plantId)) {
            throw new BusinessException(ErrorCode.CARE_RUN_ROBOT_BUSY);
        }
    }

    /**
     * 급수량이 정해져 있는지 미리 본다.
     *
     * <p>이동 명령 자체는 급수량 없이도 발행되므로, 여기서 안 보면 로봇이 스테이션까지 간 뒤에야
     * 급수 단계가 실패한다. 시연 자리에서 가장 나쁜 실패 방식이다.
     */
    private void requireWateringAmount(String plantId) {
        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND));
        if (profile.getRecommendedWateringMl() == null) {
            throw new BusinessException(ErrorCode.WATERING_AMOUNT_NOT_CONFIGURED);
        }
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private void requireOwnedPlant(String userId, String plantId) {
        plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
