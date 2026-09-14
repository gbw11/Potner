package com.potner.happiness.dto;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.HappinessGrade;
import com.potner.happiness.domain.PlantExpression;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;

import java.util.List;

/**
 * 홈 화면의 현재 상태 응답이다.
 *
 * <p>등급·문구는 앱이 쓰고, 표정은 로봇 디스플레이가 쓴다. 한 판정에서 둘이 나오므로 한 응답에
 * 담는다. 표정은 서버가 MQTT 로 로봇에 따로 밀어 주지만, 여기서도 노출해 로봇을 켜지 않고
 * 판정을 확인할 수 있게 한다.
 *
 * <p>점수는 여기 없다. 점수는 하루를 돌아보는 값이라 {@code /status-report} 로 나간다. 홈은
 * 지금을 보여주는 화면이고 하루 점수는 아직 확정되지 않았다.
 */
public record HappinessResponse(
        String plantId,

        /** 홈 화면 등급. 기준을 벗어난 센서 지표 수로 정한다. */
        HappinessGrade grade,

        /** 큰 글씨. "아주 좋아요!" */
        String headline,

        /** 작은 글씨. "햇살도 물도 딱 좋아요 :)" */
        String detail,

        /**
         * 기준을 벗어난 지표 목록이다. 정상이면 빈 목록이다.
         *
         * <p>문구를 서버가 만들어 주더라도 이걸 함께 준다. 앱이 지표별 아이콘을 붙이거나 자체
         * 문구로 바꿀 수 있어야 하고, 문구를 고칠 때마다 배포하는 상황을 벗어날 길을 남겨 둔다.
         */
        List<AbnormalMetricResponse> abnormalMetrics,

        /** 로봇 디스플레이용이다. 앱은 쓰지 않는다. */
        PlantExpression expression,
        ExpressionReason reason,
        PlantExpression baseline
) {

    public HappinessResponse {
        abnormalMetrics = List.copyOf(abnormalMetrics);
    }

    public record AbnormalMetricResponse(SensorType sensorType, SensorStatus status) {
    }
}
