package com.potner.command.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 장치 명령 설정이다.
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
         * <p>급수는 펌프 1회 최대 30초, 이동은 도킹 제한 90초 + 무선 왕복 여유로 잡는다.
         * 너무 짧으면 정상 수행이 타임아웃으로 기록되고, 너무 길면 죽은 장치 때문에 다음
         * 명령이 409 로 막히는 시간이 길어진다.
         */
        @Positive int timeoutSeconds,

        /** 타임아웃 검사 주기다. 제한 시간보다 짧아야 판정이 한 주기 이상 늦지 않는다. */
        @Positive int timeoutCheckIntervalSeconds,

        /**
         * 송풍 1회 기본 가동 시간(초)이다. 요청이 시간을 주지 않으면 이 값을 쓴다.
         *
         * <p>짧게 잡는 이유는 말리기가 "가동 → 수분 재측정 → 필요하면 재가동" 반복이기
         * 때문이다. 한 번에 오래 돌리면 과건조를 되돌릴 수 없다.
         */
        @Positive int fanRunSeconds,

        /**
         * 수분 부족 알림이 열리면 이동 → 급수 → 복귀를 자동으로 잇는다.
         *
         * <p>끄면 진행 중인 체인도 다음 회신에서 멈춘다. 오작동하는 로봇을 세울 때 서버
         * 재배포 없이 이 값만 내리면 된다.
         */
        boolean autoWaterEnabled,

        /**
         * 하루 목표 광량을 채우도록 로봇을 햇빛 자리로 보내고, 채우면 그늘(대기 장소)로
         * 되돌린다.
         */
        boolean autoSunlightEnabled,

        /**
         * 햇빛 자리로 보내는 시간대(서비스 타임존, 시)다. 창 밖에서는 보내지 않고, 창이 닫히면
         * 목표 미달이어도 되돌린다 — 해가 없는 자리에 세워 둘 이유가 없다.
         */
        @Min(0) @Max(23) int sunlightWindowStartHour,

        @Min(1) @Max(24) int sunlightWindowEndHour,

        /**
         * 사용자가 직접 이동시킨 로봇을 자동 재배치가 존중하는 시간(분)이다.
         *
         * <p>이게 없으면 사용자가 마중 자리로 부른 로봇을 다음 검사 주기에 서버가 도로
         * 끌고 간다.
         */
        @Positive int manualPlacementHoldMinutes,

        /**
         * 하루 한 장 성장 사진을 자동으로 촬영한다.
         *
         * <p>이게 꺼지면 사진이 한 장도 쌓이지 않아 포토 로그·타임랩스·생장 단계 판정·자동
         * 개화 기록이 모두 함께 멈춘다. 사진 업로드가 그 전부의 유일한 입구다.
         */
        boolean autoCaptureEnabled,

        /**
         * 촬영 시도 시간대(서비스 타임존, 시)다.
         *
         * <p>낮으로 한정하는 이유가 둘이다. 어두우면 사진이 쓸모없고, 생장 단계 추론이 어두운
         * 사진에서 오탐을 낸다.
         */
        @Min(0) @Max(23) int captureWindowStartHour,

        @Min(1) @Max(24) int captureWindowEndHour
) {

    @AssertTrue(message = "sunlight window start must be before end")
    public boolean isSunlightWindowValid() {
        return sunlightWindowStartHour < sunlightWindowEndHour;
    }

    @AssertTrue(message = "capture window start must be before end")
    public boolean isCaptureWindowValid() {
        return captureWindowStartHour < captureWindowEndHour;
    }
}
