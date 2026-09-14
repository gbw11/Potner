package com.potner.vision.application;

import com.potner.vision.domain.DetectedGrowthStage;

/**
 * 사진 판정으로 식물의 생장 단계가 올라갔다는 사실을 알린다.
 *
 * <p>{@code BloomRecordedEvent} 와 같은 이유로 엔티티가 아니라 원시 값만 담는다. 수신자는
 * 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로 영속성 컨텍스트가 이미 닫혀 있다.
 *
 * <p>단계 이름({@code lifeStageName})을 저장 시점에 넣어 넘긴다. 수신자가 다시 조회하면 그 사이에
 * 사용자가 단계를 손으로 바꿨을 때 알림 문구와 실제 단계가 어긋난다.
 */
public record GrowthStageAdvancedEvent(
        String userId,
        String plantId,
        String plantNickname,
        DetectedGrowthStage detectedStage,
        String lifeStageName
) {
}
