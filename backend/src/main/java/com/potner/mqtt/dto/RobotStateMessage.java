package com.potner.mqtt.dto;

import com.potner.device.domain.RobotState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 젯슨이 보내는 로봇 상태 보고다. 하드웨어팀이 정한 형태를 그대로 받는다.
 *
 * <p>같은 상태가 반복해서 온다. 이동하는 동안 {@code NAVIGATING} 이 계속 발행되므로 처리는
 * 멱등해야 하고, {@code messageId} 로 재전송을 거를 수는 있어도 같은 상태의 새 메시지는
 * 걸러지지 않는다. 상태 값 자체를 비교해야 한다.
 *
 * <p>{@code changedAt} 은 로그와 진단에만 쓴다. 저장하는 시각은 서버 수신 시각이다. 장치 시계를
 * 신뢰하면 급수 작업의 타임아웃 계산이 장치 시계 오차만큼 틀어진다.
 */
public record RobotStateMessage(
        @NotNull UUID messageId,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,

        @NotNull RobotState state,

        @NotNull OffsetDateTime changedAt
) {
}
