package com.potner.conversation.domain;

/**
 * 발화한 쪽이다.
 *
 * <p>OpenAI 호환 메시지 형식의 {@code role} 과 같은 값이며, 젯슨이 LLM 에 넘기는 것을 그대로
 * 받는다. 저장은 대문자로 하고 장치와 주고받을 때는 소문자를 쓴다 — 이 레포의 enum 관례는
 * 대문자이고, 장치 쪽 형식은 LLM API 규약이라 바꿀 수 없다.
 *
 * <p>{@code system} 은 담지 않는다. 시스템 프롬프트는 서버 상태와 프로필로 매번 새로 만드는
 * 값이라 이력이 아니고, 저장하면 옛 프롬프트가 되살아나 판정과 어긋난다.
 */
public enum ConversationRole {

    /** 사용자가 말한 것. 음성이면 STT 결과다. */
    USER,

    /** 로봇(초록이)이 답한 것. */
    ASSISTANT;

    /** 장치가 보내는 소문자 값을 받는다. 모르는 값은 거절한다 — 조용히 USER 로 떨어뜨리면 대화가 뒤바뀐다. */
    public static ConversationRole from(String value) {
        if (value == null) {
            return null;
        }
        for (ConversationRole role : values()) {
            if (role.name().equalsIgnoreCase(value.trim())) {
                return role;
            }
        }
        return null;
    }

    /** 장치와 LLM 이 쓰는 소문자 표기다. */
    public String wireName() {
        return name().toLowerCase();
    }
}
