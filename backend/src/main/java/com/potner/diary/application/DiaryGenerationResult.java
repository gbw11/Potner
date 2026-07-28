package com.potner.diary.application;

/**
 * 일기 한 편을 만들려는 시도의 결과다.
 *
 * <p>예외 대신 결과를 돌려준다. 호출자가 모든 식물을 도는 배치라 한 식물의 실패로 나머지가
 * 처리되지 않으면 안 된다. 일일 광량 집계와 같은 방식이다.
 */
public enum DiaryGenerationResult {

    WRITTEN,

    /** 이미 그날 일기가 있다. 배치 재실행이 정상적으로 지나는 경로이며 모델을 부르지 않는다. */
    SKIPPED_ALREADY_WRITTEN,

    /** 삭제됐거나 활성이 아닌 식물이다. */
    SKIPPED_PLANT_NOT_ACTIVE,

    /** 모델이 응답하지 않았거나, 응답이 기대한 형식이 아니었다. 키가 없을 때도 여기로 온다. */
    SKIPPED_NO_CONTENT
}
