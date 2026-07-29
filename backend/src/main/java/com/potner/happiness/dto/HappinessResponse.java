package com.potner.happiness.dto;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;

/**
 * 표정 조회 응답이다.
 *
 * <p>앱은 이걸 쓰지 않는다. 표정은 로봇 디스플레이에만 나가며, 서버가 MQTT 로 밀어 준다.
 * 이 엔드포인트는 로봇을 켜지 않고 판정을 확인하기 위한 통로다.
 *
 * <p>점수를 주지 않는다. 표정이 네 단계라 숫자로 옮길 필요가 없다.
 */
public record HappinessResponse(
        String plantId,
        PlantExpression expression,
        ExpressionReason reason,
        PlantExpression baseline
) {
}
