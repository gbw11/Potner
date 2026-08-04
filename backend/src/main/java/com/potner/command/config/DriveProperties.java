package com.potner.command.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * 손으로 로봇을 미는 조작(수동 주행)의 속도와 한 번에 움직이는 시간이다.
 *
 * <p>{@code potner.mqtt.*} 아래에 두지 않는다. 그쪽은 브로커를 켠 환경에서만 로드되어, 브로커를
 * 끈 환경에서도 떠야 하는 서비스가 여기 값을 주입받으면 컨텍스트가 뜨지 않는다.
 * {@link DeviceCommandProperties} 와 같은 방침이다.
 *
 * <p>상한을 둔 이유는 이 값이 사람 옆에서 움직이는 물리 장치의 속도라는 데 있다. 오타로 0 이
 * 하나 더 붙으면 로봇이 시연 자리에서 뛰어나간다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.drive")
public record DriveProperties(

        /** 전·후진 속도(m/s)다. 사람이 걸어서 따라잡을 수 있는 값으로 둔다. */
        @DecimalMin("0.01") @DecimalMax("0.5") BigDecimal linearMps,

        /** 제자리 회전 속도(rad/s)다. */
        @DecimalMin("0.05") @DecimalMax("1.5") BigDecimal angularRps,

        /**
         * 버튼 한 번에 움직이는 시간(ms)이다.
         *
         * <p>짧게 끊는 것이 안전장치다. 로봇은 이 시간이 지나면 스스로 멈추므로, 앱이 죽거나
         * 와이파이가 끊겨 정지 명령이 못 나가도 계속 달리지 않는다. 대신 길게 움직이려면
         * 버튼을 여러 번 눌러야 한다 — 통제권을 놓치지 않는 값이 그 불편보다 낫다.
         */
        @Positive @Max(3000) int stepMillis
) {
}
