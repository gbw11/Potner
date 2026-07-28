package com.potner.llm.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/**
 * SSAFY GMS 를 통해 OpenAI 호환 Chat Completions 를 호출한다.
 *
 * <p>GMS 는 OpenAI API 앞에 붙은 프록시라 요청·응답 형식이 OpenAI 와 같다. 그래서 전용 SDK 를
 * 넣지 않고 {@code RestClient} 로 직접 부른다. 의존성이 늘지 않고, 나중에 다른 호환 엔드포인트로
 * 옮길 때 base-url 만 바꾸면 된다. 라즈베리 수집기도 같은 프록시를 같은 형식으로 쓴다.
 *
 * <p>어떤 실패도 밖으로 내지 않는다. 네트워크 오류, 정원 초과, 형식이 어긋난 응답 모두 빈 값이
 * 된다. 호출자가 여러 식물을 도는 배치라 한 건의 실패로 멈추면 안 된다.
 */
public class GmsChatClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GmsChatClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String ROLE_SYSTEM = "system";
    private static final String ROLE_USER = "user";

    private final RestClient restClient;
    private final String model;
    private final int maxOutputTokens;

    public GmsChatClient(RestClient restClient, String model, int maxOutputTokens) {
        this.restClient = restClient;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public Optional<String> complete(LlmCompletionRequest request) {
        ChatCompletionResponse response;
        try {
            response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(toRequestBody(request))
                    .retrieve()
                    .body(ChatCompletionResponse.class);
        } catch (RestClientException exception) {
            log.warn("LLM call failed: model={}", model, exception);
            return Optional.empty();
        }

        return extractContent(response);
    }

    private ChatCompletionRequest toRequestBody(LlmCompletionRequest request) {
        return new ChatCompletionRequest(
                model,
                List.of(
                        new Message(ROLE_SYSTEM, request.systemPrompt()),
                        new Message(ROLE_USER, request.userPrompt())
                ),
                // gpt-4.1-nano 계열은 max_tokens 를 받는다. o 시리즈나 gpt-5 계열로 모델을 바꾸면
                // max_completion_tokens 로 이름이 달라져 400 이 난다. 모델 교체 시 함께 봐야 한다.
                maxOutputTokens,
                request.jsonOutput() ? new ResponseFormat("json_object") : null
        );
    }

    /**
     * 응답에서 본문만 꺼낸다.
     *
     * <p>구조가 어긋나면 빈 값이다. 정원을 넘겨 잘린 응답도 여기까지는 오는데, 잘린 JSON 은
     * 파싱 단계에서 걸러지므로 이 층에서 따로 판정하지 않는다.
     */
    private Optional<String> extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            log.warn("LLM returned no choices: model={}", model);
            return Optional.empty();
        }
        Choice choice = response.choices().getFirst();
        if (choice.message() == null || choice.message().content() == null
                || choice.message().content().isBlank()) {
            log.warn("LLM returned empty content: model={}, finishReason={}", model, choice.finishReason());
            return Optional.empty();
        }
        if ("length".equals(choice.finishReason())) {
            // 잘린 본문은 문장이 끊기거나 JSON 이 닫히지 않는다. 그대로 저장하면 사용자가 본다.
            log.warn("LLM response was truncated by the token limit: model={}, limit={}", model, maxOutputTokens);
            return Optional.empty();
        }
        return Optional.of(choice.message().content().trim());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatCompletionRequest(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens") int maxTokens,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
    }

    private record Message(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    /** 응답 중 필요한 것만 받는다. 나머지 필드는 무시한다. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record ChatCompletionResponse(List<Choice> choices) {
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
    }
}
