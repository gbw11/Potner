package com.potner.vision.application;

import java.util.Optional;

/**
 * 사진으로 생장 단계를 판정하는 창구다.
 *
 * <p>{@code potner.vision.enabled} 가 꺼져 있으면 구현이 no-op 으로 갈린다. 추론이 별도
 * 컨테이너라 로컬과 임시 CI 컨테이너에는 떠 있지 않은데, 초기화가 예외를 던지면 Health Check 가
 * 실패해 배포가 막힌다. {@code LlmClient} 와 같은 방침이다.
 *
 * <p><strong>예외를 던지지 않는다.</strong> 실패는 빈 값으로 돌아온다. 호출자는 이미 저장이 끝난
 * 사진을 뒤늦게 분석하는 비동기 리스너이므로, 추론 실패가 사진 업로드나 다음 사진의 분석에
 * 영향을 주면 안 된다.
 */
public interface GrowthStageClassifier {

    /**
     * 사진 한 장을 판정한다.
     *
     * @param imageBytes 판정할 이미지. 원본이 아니라 재생용 변형을 넘기는 것으로 충분하다
     * @param imageName  multipart 파일명으로 쓸 값. 원본 파일명이 아니라 사진 식별자를 넘긴다
     * @return 추론 결과. 비활성이거나 호출이 실패하면 비어 있다
     */
    Optional<GrowthStageClassification> classify(byte[] imageBytes, String imageName);
}
