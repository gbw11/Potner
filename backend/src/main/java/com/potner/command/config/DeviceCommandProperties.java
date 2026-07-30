package com.potner.command.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 장치 명령의 타임아웃 설정이다.
 *
 * <p>{@code potner.mqtt.*} 아래에 두지 않는다. 그쪽은 브로커를 켠 환경에서만 로드되어, 브로커를
 * 끈 환경에서도 떠야 하는 조회 서비스가 여기 값을 주입받으면 컨텍스트가 뜨지 않는다.
 * {@code potner.device.battery-reporter-type} 과 같은 방침이다.
 */
@Validated
@ConfigurationProperties(prefix = "potner.device-command")
public record DeviceCommandProperties(

        /**
         * 회신 대기 제한이다. 초과하면 TIMED_OUT 으로 끊는다.
         *
         * <p>급수는 펌프 1회 최대 30초 + 무선 왕복 여유로 잡는다. 너무 짧으면 정상 급수가
         * 타임아웃으로 기록되고, 너무 길면 죽은 장치 때문에 다음 명령이 409 로 막히는 시간이
         * 길어진다.
         */
        @Positive int timeoutSeconds,

        /** 타임아웃 검사 주기다. 제한 시간보다 짧아야 판정이 한 주기 이상 늦지 않는다. */
        @Positive int timeoutCheckIntervalSeconds
) {
}
