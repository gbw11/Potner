package com.potner.happiness.dto;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;

/**
 * 로봇에 보내는 표정 명령의 페이로드다.
 *
 * <p>주기적으로 같은 값이 다시 온다. 로봇은 마지막에 받은 값을 그대로 그리면 되고, 중복을
 * 걸러낼 필요가 없다. 그 대신 로봇이 재시작해도 한 주기 안에 표정을 되찾는다.
 *
 * <p>{@code expression} 에 로봇이 모르는 값이 올 수 있다. 서버가 표정을 늘릴 수 있으므로
 * 알 수 없는 값은 기본 표정으로 떨어뜨려야 한다. {@code reason} 도 마찬가지다.
 */
public record ExpressionCommandPayload(
        String plantId,
        PlantExpression expression,
        ExpressionReason reason
) {
}
