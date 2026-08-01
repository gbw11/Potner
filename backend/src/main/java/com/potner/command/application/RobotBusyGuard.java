package com.potner.command.application;

import com.potner.command.domain.DeviceCommandRepository;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import org.springframework.stereotype.Component;

/**
 * 로봇이 이미 무언가 하고 있는지 본다. 자동 체인 세 개가 한 대를 나눠 쓰기 때문에 필요하다.
 *
 * <p>체인 사이에는 우선순위를 두지 않는다. 먼저 시작한 쪽이 끝날 때까지 나머지는 기다리고,
 * 다음 검사 주기에 다시 본다. 급수는 알림이 열려 있는 동안, 촬영은 그날이 지나기 전까지,
 * 말리기는 과습이 풀릴 때까지 각자 재시도하므로 굶는 체인이 생기지 않는다.
 *
 * <p>회신 대기 중인 명령으로 판단한다. 체인의 단계 사이 공백은 회신 처리의 커밋 직후 다음
 * 명령이 나가므로 밀리초 단위이고, 그 틈에 다른 체인이 끼어들어도 같은 종류 명령은
 * {@code DEVICE_COMMAND_ALREADY_PENDING} 이 막는다.
 */
@Component
public class RobotBusyGuard {

    private final DeviceCommandRepository commandRepository;

    public RobotBusyGuard(DeviceCommandRepository commandRepository) {
        this.commandRepository = commandRepository;
    }

    /** 회신을 기다리는 명령이 하나라도 있으면 {@code true}. 사용자 명령도 포함해서 본다. */
    public boolean isBusy(String plantId) {
        for (DeviceCommandType type : DeviceCommandType.values()) {
            if (commandRepository.existsByPlantIdAndCommandTypeAndStatus(
                    plantId, type, DeviceCommandStatus.ISSUED)) {
                return true;
            }
        }
        return false;
    }
}
