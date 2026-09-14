package com.potner.alert.application;

import com.potner.alert.domain.AlertDeviation;
import com.potner.alert.domain.AlertMetricType;

/**
 * 이상 알림이 새로 열렸다는 사실을 알린다.
 *
 * <p>엔티티가 아니라 원시 값만 담는다. 수신자는 트랜잭션이 커밋된 뒤 다른 스레드에서 동작하므로
 * 영속성 컨텍스트가 이미 닫혀 있고, 엔티티를 넘기면 지연 로딩 시점에 예외가 난다.
 *
 * <p>발행 지점은 {@link AlertEvaluationService#openAlert}와 물 부족 알림을 여는
 * {@code StationWaterLowService} 두 곳이다. 해제는 발행하지 않는다.
 * 정상으로 돌아온 것까지 알리면 기준선 근처에서 값이 오갈 때 푸시가 두 배로 늘어난다.
 */
public record AlertOpenedEvent(
        String alertId,
        String userId,
        String plantId,
        String plantNickname,
        AlertMetricType metricType,
        AlertDeviation deviation
) {
}
