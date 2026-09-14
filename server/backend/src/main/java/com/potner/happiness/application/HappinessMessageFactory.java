package com.potner.happiness.application;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.HappinessGrade;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 홈 화면에 보여줄 두 줄 문구를 만든다.
 *
 * <p>알림 API 는 문구를 만들지 않고 {@code metricType} 과 {@code deviation} 만 준다. 여기서는
 * 반대로 서버가 문구까지 만든다. 홈 문구가 센서 여러 개를 한 문장으로 합치는 일이라 앱이
 * 조립하기 번거롭기 때문이다. 대신 {@link AbnormalMetric} 목록을 함께 내려서 앱이 자체 문구로
 * 바꿀 여지를 남긴다.
 *
 * <p>그 대가로 <strong>문구를 고치려면 배포가 필요하고, 다국어가 생기면 서버가 로케일을 알아야
 * 한다.</strong> 그때는 이 클래스를 지우고 앱이 목록으로 조립하게 돌리면 된다.
 */
@Component
public class HappinessMessageFactory {

    public HappinessMessage create(
            HappinessGrade grade,
            List<AbnormalMetric> abnormalMetrics,
            ExpressionReason reason
    ) {
        return switch (grade) {
            case GOOD -> new HappinessMessage("아주 좋아요!", goodDetail(reason));
            case FAIR -> new HappinessMessage("조금 신경 써주세요", detailOf(abnormalMetrics.getFirst()));
            case POOR -> new HappinessMessage("도움이 필요해요", poorDetail(abnormalMetrics));
            case UNKNOWN -> new HappinessMessage(
                    "상태를 확인할 수 없어요",
                    "기기가 측정값을 보내지 않고 있어요."
            );
        };
    }

    /**
     * 좋을 때는 무엇이 좋은지 말한다. "아주 좋아요" 만으로는 사용자가 자기 돌봄의 결과를
     * 읽을 수 없다.
     */
    private String goodDetail(ExpressionReason reason) {
        return switch (reason) {
            case WATERING -> "지금 물을 마시고 있어요 :)";
            case GREETING -> "당신을 반기고 있어요 :)";
            case BLOOMED -> "오늘 꽃이 피었어요!";
            case SUNLIGHT -> "햇살도 물도 딱 좋아요 :)";
            // 특별한 일이 없는 날이다. 온·습도가 기준 안이라는 사실 자체가 좋은 소식이다.
            case NONE, TEMPERATURE, HUMIDITY -> "온도와 습도가 편안해요 :)";
        };
    }

    private String detailOf(AbnormalMetric metric) {
        return switch (metric.sensorType()) {
            case TEMPERATURE -> metric.status() == SensorStatus.LOW
                    ? "주변이 조금 추워요."
                    : "주변이 조금 더워요.";
            case HUMIDITY -> metric.status() == SensorStatus.LOW
                    ? "공기가 건조해요."
                    : "공기가 습해요.";
            case SOIL_MOISTURE -> metric.status() == SensorStatus.LOW
                    ? "흙이 말랐어요."
                    : "흙이 너무 젖었어요.";
            case ILLUMINANCE -> "조도를 확인해주세요.";
        };
    }

    /**
     * 둘 이상이면 이름을 나열한다.
     *
     * <p>각 이상을 문장으로 이어 붙이면("흙이 말랐고 주변이 추워요") 조합이 지표 수의 제곱으로
     * 늘고 한국어 연결 어미까지 골라야 한다. 나열이 짧고 정확하다.
     */
    private String poorDetail(List<AbnormalMetric> abnormalMetrics) {
        String names = abnormalMetrics.stream()
                .map(AbnormalMetric::label)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return withObjectParticle(names) + " 확인해주세요.";
    }

    /**
     * 목적격 조사를 붙인다. 마지막 글자에 종성이 있으면 '을', 없으면 '를' 이다.
     *
     * <p>'토양 수분을', '온도를' 처럼 갈린다. 푸시 문구에서는 관형격('%s의')만 써서 이 문제를
     * 피했지만 여기는 지표 이름이 목적어라 피할 수 없다.
     *
     * <p>한글 음절은 유니코드에서 (초성 × 21 + 중성) × 28 + 종성 순으로 배열되므로,
     * 시작점에서의 거리를 28로 나눈 나머지가 0이면 종성이 없다.
     */
    private static String withObjectParticle(String noun) {
        if (noun.isEmpty()) {
            return noun;
        }
        char last = noun.charAt(noun.length() - 1);
        boolean hangul = last >= '가' && last <= '힣';
        boolean hasFinalConsonant = hangul && (last - '가') % 28 != 0;
        return noun + (hasFinalConsonant ? "을" : "를");
    }

    /** 홈 화면의 두 줄이다. 디자인이 큰 글씨와 작은 글씨로 나눠 보여준다. */
    public record HappinessMessage(String headline, String detail) {
    }
}
