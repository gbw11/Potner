package com.potner.mqtt.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 젯슨이 보내는 배터리 잔량 보고다.
 *
 * <p>센서 텔레메트리와 달리 {@code sensorType} 과 {@code unit} 을 받지 않는다. 토픽이 이미
 * 배터리라고 말하고 저장 컬럼이 퍼센트다. 두 필드를 받으면 서버가 값을 검증하고 어긋나면
 * 버려야 하는데, 그렇게 해서 얻는 것이 없다.
 *
 * <p>{@code batteryPercent} 는 정수다. {@code robot.battery_percent} 가 {@code TINYINT
 * UNSIGNED} 이고 CHECK 로 0~100 이 강제된다.
 *
 * <p>실수가 와도 동작한다. Jackson 이 {@code 78.9} 를 {@code 78} 로 내린다. 거부하도록 바꿀 수도
 * 있지만 배터리 퍼센트에서 소수점을 버리는 것은 잃는 것이 없고, 거부하면 쓸 수 있는 값을
 * 버린다. 범위 검사는 내림 뒤의 값에 걸리므로 {@code 101.2} 는 여전히 거부된다.
 *
 * <p>{@code messageId} 는 중복 제거용이 아니다. 잔량은 단일 컬럼 덮어쓰기라 같은 메시지가 두 번
 * 와도 결과가 같다. 로그를 장치 쪽 로그와 맞춰 보기 위한 값이다.
 */
public record BatteryMessage(
        @NotNull UUID messageId,

        @NotBlank
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}\\s]+$")
        String deviceId,

        /**
         * 0~100 정수다. 범위를 벗어나면 버린다.
         *
         * <p>0 이나 100 으로 깎지 않는다. 깎으면 고장 난 센서가 정상값을 보내는 것처럼 보여
         * 원인을 찾을 수 없다. DB CHECK 제약도 같은 범위를 강제하므로 여기서 막지 않으면 저장
         * 단계에서 예외가 난다.
         */
        @NotNull @Min(0) @Max(100) Integer batteryPercent,

        @NotNull OffsetDateTime measuredAt
) {
}
