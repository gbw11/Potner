package com.potner.diary.application;

/**
 * 일기 작성 시도의 결과다.
 *
 * <p>예외 대신 결과를 돌려주는 이유는 호출자가 배치이기 때문이다. 여러 식물을 도는 중에 한
 * 식물에서 예외가 나면 나머지가 처리되지 않는다. 일일 광량 집계가 같은 방식이다.
 */
public enum DiaryWriteResult {

    /** 새로 썼다. */
    WRITTEN,

    /** 그날 일기가 이미 있어 건너뛰었다. 배치 재실행이 정상적으로 흘러가는 경로다. */
    SKIPPED_ALREADY_WRITTEN
}
