package com.potner.command.application;

/**
 * 지도 제작(SLAM) 명령 페이로드다. 무엇을 할지는 토픽 이름이 정하므로 실을 값이 없다.
 *
 * <p>{@code potner/device/{uid}/command/mapping-start|mapping-save|mapping-cancel} 세 토픽을
 * 쓴다. 하나의 토픽에 동작 이름을 실어 보내지 않는 이유는 결과 토픽이 명령 이름과 짝을
 * 이루기 때문이다({@code result/{command}}) — 하나로 묶으면 회신도 하나가 되어 서버가
 * "무엇에 대한 회신인지"를 페이로드로 다시 풀어야 한다.
 *
 * <p>{@code requestId} 는 QoS 1 중복 수신을 걸러내는 열쇠이자 결과 대조 키다.
 * {@link CaptureCommandPayload} 와 형태가 같지만 합치지 않는다 — 촬영에 필드가 붙을 때
 * 지도 제작까지 따라 바뀌면 안 된다.
 */
public record MappingCommandPayload(String requestId) {
}
