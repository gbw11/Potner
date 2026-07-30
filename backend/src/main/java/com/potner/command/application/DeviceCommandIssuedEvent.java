package com.potner.command.application;

import com.potner.command.domain.DeviceCommandType;

import java.math.BigDecimal;

/**
 * 명령 행이 저장됐으니 MQTT 로 내보내라는 신호다.
 *
 * <p>발행을 트랜잭션 커밋 뒤로 미루는 것이 목적이다. 커밋 전에 브로커로 나가면 장치가 즉시
 * 회신했을 때(BUSY 는 몇 ms 만에 온다) 결과 처리기가 아직 없는 requestId 를 찾다 버린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다.
 *
 * @param requestedMl WATER 만. CAPTURE 는 {@code null}
 */
public record DeviceCommandIssuedEvent(
        String requestId,
        String deviceUid,
        DeviceCommandType commandType,
        BigDecimal requestedMl
) {
}
