package com.potner.llm.application;

import java.util.Optional;

/**
 * 텍스트 생성 모델 호출 창구다.
 *
 * <p>키가 없으면 구현이 no-op 으로 갈린다. 기능을 끄는 플래그를 두지 않는 이유는 Firebase
 * 발송과 같다. 임시 CI 컨테이너에 키가 없는데 초기화가 예외를 던지면 Health Check 가 실패해
 * 배포가 막힌다. 구현만 갈아끼우고 호출 경로는 항상 살려 둔다.
 *
 * <p><strong>예외를 던지지 않는다.</strong> 실패는 빈 값으로 돌아온다. 호출자가 배치라서
 * 한 건의 실패로 나머지 식물이 처리되지 않으면 안 되고, 모델 응답은 네트워크·정원·형식 어디서든
 * 어긋날 수 있어 정상 흐름의 일부로 다뤄야 한다.
 */
public interface LlmClient {

    /** 생성된 본문이다. 비활성이거나 호출이 실패하면 비어 있다. */
    Optional<String> complete(LlmCompletionRequest request);
}
