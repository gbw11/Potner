package com.potner.conversation.dto;

import com.potner.conversation.domain.RobotConversationMessage;

import java.time.LocalDateTime;

/**
 * 발화 한 줄이다.
 *
 * <p>{@code role} 은 소문자다 — 젯슨이 그대로 LLM 메시지 목록에 넣을 수 있어야 하고, 그 형식은
 * LLM API 규약이라 바꿀 수 없다.
 *
 * <p>날짜 구분선은 서버가 만들지 않는다. {@code createdAt} 을 보고 앱이 묶으면 되고, 서버가
 * 묶어 보내면 화면 구조가 응답 형식에 갇힌다.
 */
public record ConversationMessageResponse(
        int seq,
        String role,
        String content,
        LocalDateTime createdAt
) {

    public static ConversationMessageResponse from(RobotConversationMessage message) {
        return new ConversationMessageResponse(
                message.getSeq(),
                message.getRole().wireName(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}
