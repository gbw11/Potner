package com.potner.location.dto;

import com.potner.location.domain.RobotLocationType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 위치 등록 요청이다.
 *
 * <p>{@code stationCode} 는 물리 스테이션(WATER_STATION)만 갖는다. 형식은 device_uid 와 같은
 * 규칙이다 — MQTT 토픽이나 계정으로 쓰일 가능성을 열어 두기 위해서다. 종류별 필수 여부는
 * 형식 검증이 아니라 업무 규칙이라 서비스가 본다.
 */
public record RegisterRobotLocationRequest(
        @NotNull(message = "위치 종류는 필수입니다.") RobotLocationType type,
        @Size(max = 50, message = "스테이션 코드는 50자 이하여야 합니다.")
        @Pattern(
                regexp = "^[A-Za-z0-9][A-Za-z0-9_-]*$",
                message = "스테이션 코드는 영문·숫자로 시작하고 영문·숫자·하이픈·밑줄만 쓸 수 있습니다."
        )
        String stationCode
) {
}
