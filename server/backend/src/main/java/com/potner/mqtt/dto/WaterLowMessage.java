package com.potner.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 급수 스테이션의 물 부족 보고다. 스테이션 수위 센서를 읽는 라즈베리가 보낸다.
 *
 * <p>수위 퍼센트가 아니라 불리언이다. 앱이 보여줄 것은 "물을 보충하라" 한 가지뿐이라 값의
 * 정밀도가 쓰일 곳이 없고, 퍼센트를 받으면 임계값을 서버·장치 중 누가 정하는지부터 다시
 * 갈라야 한다. 판정은 센서를 아는 장치가 한다.
 *
 * <p>{@code waterLow=false} 도 보낸다. 물을 보충하면 플래그가 내려가야 다음 부족 때 알림이
 * 다시 나간다. 장치는 상태가 바뀔 때와 주기 보고 양쪽 모두 보내도 된다 — 서버 처리가
 * 멱등하다.
 *
 * <p>{@code messageId} 는 중복 제거용이 아니다. 같은 보고가 두 번 와도 결과가 같다.
 * 장치 쪽 로그와 맞춰 보기 위한 값이다.
 */
public record WaterLowMessage(
        @NotNull UUID messageId,
        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,
        @NotNull Boolean waterLow,
        @NotNull OffsetDateTime measuredAt
) {
}
