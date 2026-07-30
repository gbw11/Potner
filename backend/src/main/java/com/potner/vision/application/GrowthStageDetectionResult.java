package com.potner.vision.application;

/**
 * 사진 한 장을 분석한 결과다. 단계를 올렸는지, 올리지 않았다면 왜인지를 나눈다.
 *
 * <p>{@code SensorReadingSaveResult} 와 같은 방식이다. 예외로 알리지 않는 이유도 같다.
 * 호출자가 비동기 리스너라 대부분의 경우가 정상 흐름이며, 어느 단계에서 멈췄는지가 로그로
 * 남아야 자동 승급을 켜도 되는지 판단할 수 있다.
 */
public enum GrowthStageDetectionResult {

    /** 추론 호출이 실패했거나 비활성이다. 판정 결과가 없어 아무것도 저장하지 않는다. */
    INFERENCE_FAILED,

    /** 추론은 됐지만 판정할 검출이 없었다. 결과는 저장한다. */
    UNDETERMINED,

    /** 판정은 됐지만 신뢰도가 하한 미달이다. 관찰을 위해 판정 내용까지 저장한다. */
    LOW_CONFIDENCE,

    /** 판정된 단계가 현재 단계보다 앞서지 않는다. 되돌리지 않는다. */
    NOT_PROGRESSED,

    /**
     * 올릴 조건은 갖췄지만 자동 승급이 꺼져 있다.
     *
     * <p>기본 상태다. 이 결과가 쌓인 것을 보고 판정이 맞는지 확인한 뒤 플래그를 켠다.
     */
    AUTO_ADVANCE_DISABLED,

    /** 단계를 올렸다. 알림이 나간다. */
    ADVANCED,

    /** 사진의 식물을 찾지 못했다. 분석 중 삭제된 경우다. */
    PLANT_NOT_FOUND,

    /** 판정된 코드에 해당하는 활성 단계가 없다. 참조 데이터와 모델 클래스가 어긋났다. */
    LIFE_STAGE_NOT_FOUND,

    /** 그 종에 해당 단계의 생육 기준이 없다. 기준을 복사할 수 없어 올리지 않는다. */
    REQUIREMENT_NOT_FOUND,

    /** 식물에 적용 기준이 없다. 등록 절차가 어긋난 데이터다. */
    PROFILE_NOT_FOUND
}
