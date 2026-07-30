package com.potner.happiness.config;

import com.potner.device.domain.IotDeviceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "potner.happiness")
public record HappinessProperties(

        boolean publishEnabled,

        /**
         * 목표 조도({@code illuminance_target_lux})의 이 비율을 넘으면 "충분한 광량"으로 본다.
         *
         * <p>목표값을 그대로 쓰면 안 된다. 시드가 야외 기준이라 바질 발아기가 10,000 lux 인데
         * 실내는 창가라도 1,000~5,000 lux 라서 표정이 영영 나오지 않는다. 반대로 형광등은
         * 300~500 lux 라 절반 기준으로도 넘지 못하므로 실내등을 햇볕으로 오인하지 않는다.
         *
         * <p>시연 장소의 밝기에 맞춰 조절할 값이라 환경변수로 뺀다.
         */
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal sunlightLuxRatio,

        /** 표정을 로봇에 다시 보내는 주기다. */
        @Positive int publishIntervalSeconds,

        /**
         * 표정 발행 대상 장치의 종류다.
         *
         * <p>디스플레이가 젯슨에 달려 있다. 하드웨어 구성이 바뀌면 이미지를 다시 만들지 않고
         * 환경변수로 라즈베리로 돌릴 수 있어야 한다.
         *
         * <p>문자열이 아니라 enum 으로 받는다. 잘못된 값이면 컨텍스트가 시작될 때 실패해서
         * 바로 드러난다. 문자열로 받아 {@code valueOf} 를 부르면 주기 작업이 매번 예외를 내며
         * 로그만 쌓인다.
         */
        @NotNull IotDeviceType displayDeviceType,

        /**
         * 일별 점수에서 이상 알림 한 지표당 깎는 점수다.
         *
         * <p>기본 5다. 지표 5종이 전부 이상이면 75점이 되고, 알림 하나면 95점이다. 프로퍼티로
         * 뺀 이유는 감점 폭이 기획 감각의 문제라서 코드를 고치지 않고 조절해야 하기 때문이다.
         */
        @Positive int alertPenalty,

        /** 그날 꽃이 폈을 때의 가점이다. 유일한 가점이다. */
        @Positive int bloomBonus
) {
}
