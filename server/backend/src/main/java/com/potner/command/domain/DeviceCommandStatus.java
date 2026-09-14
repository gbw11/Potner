package com.potner.command.domain;

import java.util.Optional;

/**
 * 명령 한 건의 상태다. {@code ISSUED} 로 시작해 장치 회신이나 타임아웃으로 끝난다.
 *
 * <p>{@code OK}/{@code ERROR}/{@code BUSY}/{@code SKIPPED} 는 라즈베리 회신의 {@code status}
 * 문자열 그대로다. 서버 어휘로 바꾸지 않는 이유는, 장치 쪽 구현이 먼저 있었고 값을 옮겨 적는
 * 층이 생기면 어긋날 자리만 늘기 때문이다. <strong>장치가 보내는 값을 하나라도 빠뜨리면 그
 * 회신은 반영되지 않고 명령이 타임아웃까지 매달린다</strong> — 목록의 출처는 라즈베리의
 * {@code src/mqtt/*_command.py} 이고, {@code docs/DEVICE-MQTT.md} 의 표와 함께 고쳐야 한다.
 *
 * <p>{@code BUSY} 를 실패와 나눈다. 장치가 앞선 작업 중이라 거부한 것이라 잠시 뒤 다시 보내면
 * 되고, {@code ERROR} 는 펌프 오류처럼 재시도해도 같은 결과일 가능성이 크다.
 */
public enum DeviceCommandStatus {

    /** 발행됨. 회신 대기 중이다. */
    ISSUED,

    /** 장치가 수행을 완료했다. */
    OK,

    /** 장치가 실패를 보고했다. */
    ERROR,

    /** 장치가 앞선 작업 중이라 거부했다. */
    BUSY,

    /**
     * 장치가 자기 판단으로 수행하지 않았다. 급수만 보낸다.
     *
     * <p>라즈베리의 과급수 가드다 — 1회 상한·급수 간격·24시간 예산 중 하나에 걸리면 펌프를
     * 돌리지 않고 이 상태로 회신한다({@code dispensedMl=0}). 사유는 회신의 {@code skipCode} 에
     * 있지만 서버는 그것까지 저장하지 않는다.
     *
     * <p><strong>실패가 아니다.</strong> 장치는 멀쩡하고 물을 주지 않기로 판단했을 뿐이라
     * {@link #continuesChain()} 이 {@code OK} 와 같이 취급한다. {@code ERROR} 로 옮겨 적으면
     * 체인이 끊겨 로봇이 스테이션에 남는다.
     */
    SKIPPED,

    /** 회신이 제한 시간 안에 오지 않아 서버가 끊었다. */
    TIMED_OUT;

    /**
     * 장치 회신의 status 문자열을 옮긴다.
     *
     * @return 대응 상태. 장치가 모르는 값을 보내면 비어 있다 — 조용히 넘기지 않고 걸러낸다
     */
    public static Optional<DeviceCommandStatus> fromDeviceReport(String reported) {
        if (reported == null) {
            return Optional.empty();
        }
        return switch (reported) {
            case "OK" -> Optional.of(OK);
            case "ERROR" -> Optional.of(ERROR);
            case "BUSY" -> Optional.of(BUSY);
            case "SKIPPED" -> Optional.of(SKIPPED);
            default -> Optional.empty();
        };
    }

    /** 회신이나 타임아웃이 이미 반영된 상태인지. 이 상태에서는 회신을 다시 받지 않는다. */
    public boolean isTerminal() {
        return this == OK || this == ERROR || this == BUSY || this == SKIPPED;
    }

    /**
     * 이 단계를 끝난 것으로 보고 자동 케어 체인의 다음 단계를 이어도 되는지.
     *
     * <p>{@code OK} 와 {@code SKIPPED} 를 함께 둔다. 둘의 공통점은 <strong>로봇이 어디 있는지
     * 서버가 안다</strong>는 것이다 — 지시한 자리에 서서 작업을 마쳤거나, 하지 않기로 판단했을
     * 뿐이다. 그래서 다음 명령을 그 자리 기준으로 내보낼 수 있다.
     *
     * <p>나머지는 이을 수 없다. {@code ERROR}·{@code TIMED_OUT} 은 이동 중이었다면 로봇의
     * 위치를 알 수 없고, {@code BUSY} 는 장치가 아직 앞선 작업을 하고 있다.
     */
    public boolean continuesChain() {
        return this == OK || this == SKIPPED;
    }
}
