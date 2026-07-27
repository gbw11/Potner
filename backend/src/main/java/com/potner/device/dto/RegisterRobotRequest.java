package com.potner.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 로봇 등록 요청이다.
 *
 * <p>{@code deviceUid} 는 사용자가 로봇 스티커에서 읽어 입력한다. MQTT 토픽 세그먼트로 쓰이므로
 * 토픽을 깨뜨리지 않는 문자만 허용한다. 슬래시나 와일드카드가 들어오면 구독 경로가 어긋난다.
 */
public record RegisterRobotRequest(
        @NotBlank
        @Size(max = 100)
        @Pattern(
                regexp = "[A-Za-z0-9][A-Za-z0-9_-]*",
                message = "장치 식별자는 영문, 숫자, 하이픈, 밑줄만 사용할 수 있습니다."
        )
        String deviceUid,

        @NotBlank
        @Size(max = 50)
        String name
) {
}
