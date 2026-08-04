package com.potner.command.dto;

import com.potner.command.domain.DriveDirection;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 방향 버튼 한 번을 보내는 요청이다.
 *
 * <p>속도를 받지 않는다. 사람 옆에서 움직이는 물리 장치의 속도는 조작하는 쪽이 정할 값이
 * 아니므로 {@code potner.drive.*} 만이 출처다.
 *
 * @param durationMs 이 시간(ms)만 움직이고 멈춘다. 생략하면 서버 기본값
 *                   ({@code potner.drive.step-millis})이다. 상한을 두는 이유는 이 값이 곧
 *                   "정지 명령이 사라졌을 때 로봇이 계속 달리는 시간" 이기 때문이다.
 *                   {@link DriveDirection#STOP} 에는 쓰이지 않는다
 */
public record DriveRequest(
        @NotNull(message = "방향은 필수입니다.") DriveDirection direction,
        @Min(value = 100, message = "이동 시간은 100ms 이상이어야 합니다.")
        @Max(value = 3000, message = "이동 시간은 3000ms 이하여야 합니다.")
        Integer durationMs
) {
}
