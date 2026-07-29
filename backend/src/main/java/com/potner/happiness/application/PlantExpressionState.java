package com.potner.happiness.application;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;

/**
 * 판정 결과다.
 *
 * <p>{@code baseline} 을 따로 담는 이유는 부스트가 걷힌 뒤 어떤 표정으로 돌아갈지 보여주기
 * 위해서다. 급수 중이라 매우행복인데 온도가 기준을 벗어나 있으면, 물을 다 마신 뒤 우울로
 * 떨어진다는 사실을 사용자가 미리 알 수 있다.
 */
public record PlantExpressionState(
        PlantExpression expression,
        ExpressionReason reason,
        PlantExpression baseline
) {

    public static PlantExpressionState of(PlantExpression expression, ExpressionReason reason) {
        return new PlantExpressionState(expression, reason, expression);
    }

    /** 바탕 위에 부스트를 덮는다. 부스트가 이긴다. */
    public PlantExpressionState boostedTo(PlantExpression boosted, ExpressionReason reason) {
        return new PlantExpressionState(boosted, reason, baseline);
    }
}
