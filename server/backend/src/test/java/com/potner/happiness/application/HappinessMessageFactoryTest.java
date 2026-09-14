package com.potner.happiness.application;

import com.potner.happiness.domain.ExpressionReason;
import com.potner.happiness.domain.HappinessGrade;
import com.potner.sensor.domain.SensorStatus;
import com.potner.sensor.domain.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HappinessMessageFactoryTest {

    private final HappinessMessageFactory messageFactory = new HappinessMessageFactory();

    @Test
    void goodStateSaysWhatIsGood() {
        // "아주 좋아요" 만으로는 사용자가 자기 돌봄의 결과를 읽을 수 없다.
        assertThat(create(HappinessGrade.GOOD, List.of(), ExpressionReason.SUNLIGHT))
                .extracting(
                        HappinessMessageFactory.HappinessMessage::headline,
                        HappinessMessageFactory.HappinessMessage::detail)
                .containsExactly("아주 좋아요!", "햇살도 물도 딱 좋아요 :)");

        assertThat(create(HappinessGrade.GOOD, List.of(), ExpressionReason.BLOOMED).detail())
                .isEqualTo("오늘 꽃이 피었어요!");
        assertThat(create(HappinessGrade.GOOD, List.of(), ExpressionReason.WATERING).detail())
                .contains("물을 마시고");
        assertThat(create(HappinessGrade.GOOD, List.of(), ExpressionReason.NONE).detail())
                .isEqualTo("온도와 습도가 편안해요 :)");
    }

    @Test
    void oneAbnormalMetricNamesTheProblemAndItsDirection() {
        assertThat(fairDetail(SensorType.SOIL_MOISTURE, SensorStatus.LOW)).isEqualTo("흙이 말랐어요.");
        assertThat(fairDetail(SensorType.SOIL_MOISTURE, SensorStatus.HIGH))
                .isEqualTo("흙이 너무 젖었어요.");
        assertThat(fairDetail(SensorType.TEMPERATURE, SensorStatus.LOW)).isEqualTo("주변이 조금 추워요.");
        assertThat(fairDetail(SensorType.TEMPERATURE, SensorStatus.HIGH)).isEqualTo("주변이 조금 더워요.");
        assertThat(fairDetail(SensorType.HUMIDITY, SensorStatus.LOW)).isEqualTo("공기가 건조해요.");
        assertThat(fairDetail(SensorType.HUMIDITY, SensorStatus.HIGH)).isEqualTo("공기가 습해요.");
    }

    @Test
    void objectParticleFollowsTheLastSyllable() {
        // '수분을', '온도를' 처럼 갈린다. 종성 유무로 골라야 문장이 어색해지지 않는다.
        assertThat(poorDetail(
                new AbnormalMetric(SensorType.TEMPERATURE, SensorStatus.LOW),
                new AbnormalMetric(SensorType.SOIL_MOISTURE, SensorStatus.LOW)))
                .isEqualTo("온도, 토양 수분을 확인해주세요.");

        assertThat(poorDetail(
                new AbnormalMetric(SensorType.SOIL_MOISTURE, SensorStatus.LOW),
                new AbnormalMetric(SensorType.TEMPERATURE, SensorStatus.LOW)))
                .isEqualTo("토양 수분, 온도를 확인해주세요.");
    }

    @Test
    void unknownStateDoesNotPretendEverythingIsFine() {
        // 기기가 꺼져 있는 동안 "아주 좋아요" 를 보여주면 사용자가 방치한다.
        assertThat(create(HappinessGrade.UNKNOWN, List.of(), ExpressionReason.NONE))
                .extracting(
                        HappinessMessageFactory.HappinessMessage::headline,
                        HappinessMessageFactory.HappinessMessage::detail)
                .containsExactly("상태를 확인할 수 없어요", "기기가 측정값을 보내지 않고 있어요.");
    }

    @ParameterizedTest
    @EnumSource(HappinessGrade.class)
    void everyGradeHasBothLines(HappinessGrade grade) {
        HappinessMessageFactory.HappinessMessage message = create(
                grade,
                List.of(new AbnormalMetric(SensorType.TEMPERATURE, SensorStatus.LOW)),
                ExpressionReason.NONE
        );

        assertThat(message.headline()).isNotBlank();
        assertThat(message.detail()).isNotBlank();
    }

    private HappinessMessageFactory.HappinessMessage create(
            HappinessGrade grade,
            List<AbnormalMetric> abnormalMetrics,
            ExpressionReason reason
    ) {
        return messageFactory.create(grade, abnormalMetrics, reason);
    }

    private String fairDetail(SensorType sensorType, SensorStatus status) {
        return create(
                HappinessGrade.FAIR,
                List.of(new AbnormalMetric(sensorType, status)),
                ExpressionReason.NONE
        ).detail();
    }

    private String poorDetail(AbnormalMetric... metrics) {
        return create(HappinessGrade.POOR, List.of(metrics), ExpressionReason.NONE).detail();
    }
}
