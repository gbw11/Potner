package com.potner.vision.domain;

import java.util.Optional;

/**
 * 추론 모델이 판정하는 생장 단계다.
 *
 * <p>모델 클래스 3종({@code germination}, {@code vegetative}, {@code flowering})과 1:1 로
 * 대응한다. 이름을 그대로 쓰지 않고 열거형으로 받는 이유는, 모델이 클래스를 늘렸을 때 서버가
 * 조용히 넘기지 않고 알 수 없는 값으로 걸러내게 하려는 것이다.
 *
 * <p>{@code lifeStageCode} 는 {@code plant_life_stage.code} 다. 모델 클래스보다 참조 데이터의
 * 단계가 더 세분화(9종)되어 있어 전사가 아니다. 모델이 구분하지 못하는 단계(유묘기·성숙기 등)로는
 * 자동 승급하지 않는다.
 */
public enum DetectedGrowthStage {

    GERMINATION("germination", "GERMINATION"),
    VEGETATIVE("vegetative", "VEGETATIVE"),
    FLOWERING("flowering", "FLOWERING");

    private final String modelClassName;
    private final String lifeStageCode;

    DetectedGrowthStage(String modelClassName, String lifeStageCode) {
        this.modelClassName = modelClassName;
        this.lifeStageCode = lifeStageCode;
    }

    /**
     * 모델이 준 클래스 이름을 열거형으로 바꾼다.
     *
     * <p>대소문자를 구분하지 않는다. 모델 쪽 클래스 이름이 소문자라는 전제에 기대지 않기 위한
     * 것으로, 서버 enum 과 장치 문자열을 대조하는 MQTT 쪽과 방침이 다르다. 그쪽은 장치가 서버
     * 명세를 따르지만 여기서는 서버가 모델 산출물을 받는 쪽이다.
     *
     * @return 대응하는 단계. 모델이 모르는 클래스를 주면 빈 값이다
     */
    public static Optional<DetectedGrowthStage> fromModelClassName(String className) {
        if (className == null || className.isBlank()) {
            return Optional.empty();
        }
        String normalized = className.trim().toLowerCase();
        for (DetectedGrowthStage stage : values()) {
            if (stage.modelClassName.equals(normalized)) {
                return Optional.of(stage);
            }
        }
        return Optional.empty();
    }

    public String modelClassName() {
        return modelClassName;
    }

    public String lifeStageCode() {
        return lifeStageCode;
    }
}
