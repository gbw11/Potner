package com.potner.bloom.application;

import java.time.LocalDate;

/**
 * 개화 기록이 새로 남았다는 사실을 알린다.
 *
 * <p>{@code AlertOpenedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>{@code firstBloom} 을 저장 시점에 계산해 넘긴다. 수신자가 다시 세면 그 사이에 두 번째
 * 기록이 들어왔을 때 첫 꽃이 첫 꽃이 아니게 된다.
 *
 * <p>{@code bloomDate} 는 서비스 타임존 기준 날짜다. 푸시를 누른 사용자를 그날 포토 로그로
 * 보내는 데 쓴다 — 수신자가 {@code bloomId} 로 기록을 다시 조회하지 않아도 되게 여기 담는다.
 */
public record BloomRecordedEvent(
        String bloomId,
        String userId,
        String plantId,
        String plantNickname,
        LocalDate bloomDate,
        boolean firstBloom
) {
}
