package com.potner.llm.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 키가 없을 때 쓰는 구현이다. 아무것도 부르지 않고 빈 값을 돌려준다.
 *
 * <p>로컬과 임시 CI 컨테이너가 이 구현으로 동작한다. 프롬프트 조립까지는 그대로 돌기 때문에
 * 키 없이도 그 앞 단계는 검증된다.
 */
public class DisabledLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(DisabledLlmClient.class);

    @Override
    public Optional<String> complete(LlmCompletionRequest request) {
        log.info(
                "LLM call skipped because no API key is configured: systemPromptChars={}, userPromptChars={}",
                request.systemPrompt().length(),
                request.userPrompt().length()
        );
        return Optional.empty();
    }
}
