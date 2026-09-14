package com.potner.command.application;

import java.math.BigDecimal;

/**
 * 이동 명령 페이로드다. 필드 이름이 그대로 JSON 키가 된다.
 *
 * <p>목적지 이름과 좌표를 함께 보낸다. 이름은 로봇 로그와 상태 보고에서 사람이 읽을 값이고,
 * 로봇이 실제로 쓰는 것은 좌표다(map 프레임, m·rad). 좌표의 유일한 출처는 서버의
 * robot_location 이다 — 로봇 파라미터의 station_poses 를 쓰면 출처가 둘이 되어 어긋난다.
 */
public record NavigateCommandPayload(
        String destination,
        BigDecimal x,
        BigDecimal y,
        BigDecimal yaw,
        String requestId
) {
}
