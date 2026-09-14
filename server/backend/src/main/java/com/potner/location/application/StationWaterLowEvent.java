package com.potner.location.application;

/**
 * 급수 스테이션이 물 부족 상태로 바뀌었다는 사실을 알린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>부족으로 <strong>바뀔 때만</strong> 발행된다. 반복 보고는 이벤트가 되지 않는다.
 */
public record StationWaterLowEvent(
        String userId,
        String robotId,
        String stationCode
) {
}
