package com.potner.command.dto;

import com.potner.command.domain.DeviceCommandType;
import com.potner.location.domain.RobotLocationType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 명령 발행 요청이다.
 *
 * <p>급수량을 받지 않는다. 양은 식물별 적용 생육 기준({@code recommendedWateringMl})이 유일한
 * 출처다. 요청 본문에 양을 받으면 케어 설정 화면의 값과 실제 급수가 어긋난 채 운영된다.
 *
 * @param destination NAVIGATE 만. 그 외 종류에 넣으면 400 이다
 * @param seconds     FAN 만. 생략하면 서버 기본값을 쓴다. 그 외 종류에 넣으면 400 이다
 */
public record IssueDeviceCommandRequest(
        @NotNull(message = "명령 종류는 필수입니다.") DeviceCommandType type,
        RobotLocationType destination,
        @Min(value = 1, message = "가동 시간은 1초 이상이어야 합니다.")
        @Max(value = 300, message = "가동 시간은 300초 이하여야 합니다.")
        Integer seconds
) {
}
