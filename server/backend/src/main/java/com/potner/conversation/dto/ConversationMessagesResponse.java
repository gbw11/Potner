package com.potner.conversation.dto;

import java.util.List;

/**
 * 발화 한 페이지다.
 *
 * <p>{@code messages} 는 <strong>오래된 것부터</strong> 담긴다. 화면은 위에서 아래로 시간이
 * 흐르므로 앱이 되집을 일이 없다.
 *
 * <p>{@code nextBeforeSeq} 는 이 페이지보다 더 오래된 것을 가져올 때 그대로 {@code beforeSeq} 에
 * 넣을 값이다(= 이 페이지에서 가장 오래된 발화의 seq). {@code hasMore} 가 false 면 더 없다.
 *
 * <p>page/size 를 쓰지 않는 이유가 여기 있다. 대화는 뒤에 계속 붙으므로, 스크롤하는 동안 새
 * 발화가 들어오면 offset 이 밀려 같은 발화가 두 번 보이거나 빠진다. seq 커서는 새 발화가
 * 들어와도 흔들리지 않는다.
 */
public record ConversationMessagesResponse(
        String conversationId,
        String plantId,
        List<ConversationMessageResponse> messages,
        Integer nextBeforeSeq,
        boolean hasMore
) {

    /** 아직 대화가 없는 경우다. 404 가 아니라 빈 목록이다 — 채팅 화면은 비어 있는 것이 정상 상태다. */
    public static ConversationMessagesResponse empty(String plantId) {
        return new ConversationMessagesResponse(null, plantId, List.of(), null, false);
    }
}
