package com.potner.alert.application;

public enum AlertEvaluationResult {

    /** 새 이상 상태를 감지해 Alert를 생성했다. */
    CREATED,

    /** 이상 방향이 반대로 바뀌어 기존 Alert를 해제하고 새로 생성했다. */
    SWITCHED,

    /** 정상 범위로 복귀해 기존 Alert를 해제했다. */
    RESOLVED,

    /** 상태 변화가 없다. 기준선 근처 왕복도 여기에 해당한다. */
    UNCHANGED,

    /** 판정에 필요한 최근 측정값이 부족하다. 장치가 조용한 경우를 포함한다. */
    INSUFFICIENT_DATA,

    /** 순간값으로 판정하지 않는 지표이거나 적용 기준이 없다. */
    NOT_APPLICABLE,

    /** 삭제된 식물이거나 존재하지 않는다. */
    PLANT_NOT_ACTIVE,

    /** 적용 생육 기준을 찾을 수 없다. */
    GROWTH_PROFILE_NOT_FOUND
}
