package com.potner.conversation.application;

import com.potner.conversation.config.ConversationProperties;
import com.potner.conversation.domain.RobotConversation;
import com.potner.conversation.domain.RobotConversationMessage;
import com.potner.conversation.domain.RobotConversationMessageRepository;
import com.potner.conversation.dto.ConversationMessageResponse;
import com.potner.conversation.dto.ConversationMessagesResponse;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 발화 한 페이지를 읽는다. 장치 복원과 앱 스크롤백이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>커서는 {@code seq} 다. 대화는 뒤에 계속 붙으므로 offset(page/size)을 쓰면 스크롤하는 동안
 * 새 발화가 들어와 항목이 밀려 같은 발화가 두 번 보이거나 빠진다.
 */
@Component
public class ConversationMessageReader {

    private final RobotConversationMessageRepository messageRepository;
    private final ConversationProperties properties;

    public ConversationMessageReader(
            RobotConversationMessageRepository messageRepository,
            ConversationProperties properties
    ) {
        this.messageRepository = messageRepository;
        this.properties = properties;
    }

    /**
     * @param beforeSeq 이 값보다 작은 seq 만 본다. {@code null} 이면 가장 최근부터
     * @param size      {@code null} 이면 {@code defaultSize}. 상한을 넘으면 상한으로 줄인다
     */
    public ConversationMessagesResponse read(
            RobotConversation conversation,
            Integer beforeSeq,
            Integer size,
            int defaultSize
    ) {
        int limit = resolveSize(size, defaultSize);

        // 한 건 더 읽어 더 있는지 판단한다. 별도 count 쿼리를 두면 그 사이에 발화가 들어와
        // hasMore 와 실제가 어긋날 수 있다.
        List<RobotConversationMessage> found = messageRepository
                .findByConversationIdAndSeqLessThanOrderBySeqDesc(
                        conversation.getId(),
                        beforeSeq == null ? Integer.MAX_VALUE : beforeSeq,
                        Limit.of(limit + 1)
                );

        boolean hasMore = found.size() > limit;
        List<RobotConversationMessage> page = hasMore ? found.subList(0, limit) : found;

        // 조회는 내림차순("최근 N개")이지만 화면과 LLM 프롬프트는 시간순이라 되집는다.
        List<ConversationMessageResponse> messages = new ArrayList<>(page.size());
        for (int i = page.size() - 1; i >= 0; i--) {
            messages.add(ConversationMessageResponse.from(page.get(i)));
        }

        // 다음 페이지(더 오래된 쪽)의 커서는 이 페이지에서 가장 오래된 발화의 seq 다.
        Integer nextBeforeSeq = page.isEmpty() ? null : page.getLast().getSeq();

        return new ConversationMessagesResponse(
                conversation.getId(),
                conversation.getPlantId(),
                messages,
                nextBeforeSeq,
                hasMore
        );
    }

    private int resolveSize(Integer requested, int defaultSize) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultSize, properties.maxPageSize());
        }
        return Math.min(requested, properties.maxPageSize());
    }
}
