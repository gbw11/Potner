package com.potner.command.application;

import java.math.BigDecimal;

/**
 * 수동 주행 명령의 페이로드다. 필드 이름이 그대로 JSON 키가 된다.
 *
 * <p>방향 이름과 속도를 함께 보낸다. 이름은 로봇 로그에서 사람이 읽을 값이고, 로봇이 실제로
 * 쓰는 것은 {@code linearMps} 와 {@code angularRps} 다. 이름만 보내면 속도를 로봇 파라미터에서
 * 읽어야 하는데, 그러면 속도의 출처가 둘이 되어 서버 설정을 바꿔도 로봇이 안 따라온다.
 * {@link NavigateCommandPayload} 가 좌표를 함께 보내는 것과 같은 이유다.
 *
 * <p>{@code durationMs} 가 지나면 로봇이 스스로 멈춰야 한다. 이것이 유일한 안전장치다 — 앱이
 * 죽거나 네트워크가 끊겨 {@code STOP} 이 못 나가는 경우를 서버가 막을 방법이 없다.
 *
 * <p>{@code requestId} 는 QoS 1 중복 수신을 걸러내는 열쇠다. 같은 값이 두 번 오면 두 번
 * 움직이지 말고 무시해야 한다.
 */
public record DriveCommandPayload(
        String direction,
        BigDecimal linearMps,
        BigDecimal angularRps,
        int durationMs,
        String requestId
) {
}
