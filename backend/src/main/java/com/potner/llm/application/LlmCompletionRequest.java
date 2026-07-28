package com.potner.llm.application;

/**
 * LLM 한 번 호출에 필요한 것이다.
 *
 * <p>대화 이력을 담지 않는다. 서버가 LLM 을 쓰는 곳은 하루에 한 번 도는 배치이고 매 호출이
 * 독립적이다. 여러 턴이 필요한 대화는 장치 쪽에서 처리한다.
 *
 * @param jsonOutput 응답을 JSON 객체로 강제할지 여부. 일기처럼 필드가 정해진 결과를 받을 때
 *                   켠다. 켜면 프롬프트에도 원하는 필드를 적어야 한다. 모델은 형식만 맞출 뿐
 *                   어떤 필드를 넣을지는 모른다.
 */
public record LlmCompletionRequest(
        String systemPrompt,
        String userPrompt,
        boolean jsonOutput
) {

    public static LlmCompletionRequest json(String systemPrompt, String userPrompt) {
        return new LlmCompletionRequest(systemPrompt, userPrompt, true);
    }

    public static LlmCompletionRequest text(String systemPrompt, String userPrompt) {
        return new LlmCompletionRequest(systemPrompt, userPrompt, false);
    }
}
