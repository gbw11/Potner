package com.potner.command.domain;

import java.util.Optional;

/**
 * 명령 한 건의 상태다. {@code ISSUED} 로 시작해 장치 회신이나 타임아웃으로 끝난다.
 *
 * <p>{@code OK}/{@code ERROR}/{@code BUSY} 는 라즈베리 회신의 {@code status} 문자열 그대로다.
 * 서버 어휘로 바꾸지 않는 이유는, 장치 쪽 구현이 먼저 있었고 값을 옮겨 적는 층이 생기면
 * 어긋날 자리만 늘기 때문이다.
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
            default -> Optional.empty();
        };
    }

    /** 회신이나 타임아웃이 이미 반영된 상태인지. 이 상태에서는 회신을 다시 받지 않는다. */
    public boolean isTerminal() {
        return this == OK || this == ERROR || this == BUSY;
    }
}
