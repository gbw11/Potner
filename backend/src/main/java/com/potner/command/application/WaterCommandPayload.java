package com.potner.command.application;

import java.math.BigDecimal;

/**
 * 급수 명령 페이로드다. 필드 이름이 그대로 JSON 키가 된다.
 *
 * <p>키가 {@code ml} 인 이유는 라즈베리 수신기가 그 이름을 기본으로 읽기 때문이다
 * ({@code amountMl} 등도 허용하지만 기본 키에 맞춘다). {@code requestId} 는 장치가 회신에
 * 그대로 되돌려 서버가 명령과 결과를 대조하는 열쇠다.
 */
public record WaterCommandPayload(BigDecimal ml, String requestId) {
}
