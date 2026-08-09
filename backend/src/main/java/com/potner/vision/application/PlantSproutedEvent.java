package com.potner.vision.application;

/**
 * 사진 판정으로 새싹이 확인됐다는 사실을 알린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>단계 승급과 별개다. 발아기는 생장 단계의 맨 아래라({@code sort_order = 5}) 올라갈 곳이
 * 없어 승급 경로로는 이 사실을 알릴 수 없다. 그런데 씨앗을 심고 기다리던 사용자에게 싹이
 * 텄다는 것은 개화만큼 큰 소식이므로 별도 경로를 둔다. 개화 기록이 승급과 무관하게 발행되는
 * 것과 같은 구조다.
 */
public record PlantSproutedEvent(
        String userId,
        String plantId,
        String plantNickname
) {
}
