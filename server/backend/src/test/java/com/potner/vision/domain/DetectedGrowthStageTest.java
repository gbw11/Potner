package com.potner.vision.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectedGrowthStageTest {

    @Test
    void mapsEveryModelClassToAnActiveLifeStageCode() {
        // 모델 클래스 3종이 참조 데이터의 단계 코드와 1:1 로 대응해야 한다. 어긋나면 판정이
        // LIFE_STAGE_NOT_FOUND 로 떨어지는데 그건 런타임에야 드러난다.
        assertThat(DetectedGrowthStage.fromModelClassName("germination"))
                .contains(DetectedGrowthStage.GERMINATION);
        assertThat(DetectedGrowthStage.fromModelClassName("vegetative"))
                .contains(DetectedGrowthStage.VEGETATIVE);
        assertThat(DetectedGrowthStage.fromModelClassName("flowering"))
                .contains(DetectedGrowthStage.FLOWERING);

        assertThat(DetectedGrowthStage.GERMINATION.lifeStageCode()).isEqualTo("GERMINATION");
        assertThat(DetectedGrowthStage.VEGETATIVE.lifeStageCode()).isEqualTo("VEGETATIVE");
        assertThat(DetectedGrowthStage.FLOWERING.lifeStageCode()).isEqualTo("FLOWERING");
    }

    @Test
    void acceptsAnyCasingFromTheModel() {
        // 서버가 모델 산출물을 받는 쪽이라 저쪽 표기에 기대지 않는다. 장치가 서버 명세를 따르는
        // MQTT 쪽과 방침이 다르다.
        assertThat(DetectedGrowthStage.fromModelClassName("VEGETATIVE"))
                .contains(DetectedGrowthStage.VEGETATIVE);
        assertThat(DetectedGrowthStage.fromModelClassName("  Flowering  "))
                .contains(DetectedGrowthStage.FLOWERING);
    }

    @Test
    void returnsEmptyForAClassTheServerDoesNotKnow() {
        // 모델이 클래스를 늘렸을 때 조용히 넘기지 않고 걸러내야 한다.
        assertThat(DetectedGrowthStage.fromModelClassName("fruiting")).isEmpty();
        assertThat(DetectedGrowthStage.fromModelClassName("")).isEmpty();
        assertThat(DetectedGrowthStage.fromModelClassName(null)).isEmpty();
    }
}
