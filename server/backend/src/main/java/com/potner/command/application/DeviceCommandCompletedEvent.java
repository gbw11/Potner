package com.potner.command.application;

import com.potner.command.domain.CommandInitiator;
import com.potner.command.domain.CommandPurpose;
import com.potner.command.domain.DeviceCommandStatus;
import com.potner.command.domain.DeviceCommandType;
import com.potner.location.domain.RobotLocationType;

/**
 * 장치 회신이 명령 행에 반영됐다는 사실을 알린다.
 *
 * <p>{@code AlertOpenedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤에 돌므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>회신이 반영될 때만 발행된다. 타임아웃({@code TIMED_OUT})은 스케줄러가 벌크 UPDATE 로
 * 처리해 이 이벤트가 없다 — 자동 케어 체인은 타임아웃에서 자연히 멈추고, 그게 의도다.
 * 회신 없는 장치에 다음 명령을 이어 봐야 그것도 타임아웃이 된다.
 */
public record DeviceCommandCompletedEvent(
        String requestId,
        String plantId,
        DeviceCommandType commandType,
        CommandInitiator initiator,

        /** 어느 자동 체인의 단계인지. 사용자 명령은 {@code null} 이라 어떤 체인도 이어받지 않는다. */
        CommandPurpose purpose,

        RobotLocationType destination,
        DeviceCommandStatus status
) {
}
