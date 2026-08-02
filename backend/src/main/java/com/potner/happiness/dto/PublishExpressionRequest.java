package com.potner.happiness.dto;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.PlantExpression;
import jakarta.validation.constraints.NotNull;

/**
 * 표정을 손으로 한 번 발행하는 요청이다.
 *
 * <p>지속 시간을 받지 않는다. 주기 발행({@code potner.happiness.publish-interval-seconds},
 * 기본 30초)이 다음 차례에 자기 판정으로 덮어쓰므로 이 표정은 한 주기만 유지된다. 시연에서
 * 로봇 얼굴이 실제로 바뀌는지 확인하는 용도이며, 오래 붙잡아 두려면 주기 발행보다 우선하는
 * 오버라이드가 필요하다 — 그건 별개 작업이다.
 *
 * @param reason 말풍선 문구를 고르는 힌트다. 생략하면 {@link ExpressionReason#NONE} 이다
 */
public record PublishExpressionRequest(
        @NotNull(message = "표정은 필수입니다.") PlantExpression expression,
        ExpressionReason reason
) {
}
