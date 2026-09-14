package com.potner.conversation.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RobotConversationMessageRepository
        extends JpaRepository<RobotConversationMessage, String> {

    /**
     * 다음 {@code seq} 를 정하는 데 쓴다.
     *
     * <p>발화 수를 세지 않고 최대값을 읽는다. 나중에 오래된 발화를 지우는 정리 배치가 붙어도
     * 순서가 되돌아가 UNIQUE 와 충돌하지 않는다.
     */
    @Query("select max(m.seq) from RobotConversationMessage m where m.conversationId = :conversationId")
    Optional<Integer> findMaxSeq(String conversationId);

    /**
     * 최근 발화를 뒤에서부터 가져온다. 커서({@code beforeSeq}) 보다 앞선 것만 본다.
     *
     * <p>내림차순인 이유는 "최근 N개" 를 뽑아야 하기 때문이다. 호출자가 시간순으로 되집는다.
     */
    List<RobotConversationMessage> findByConversationIdAndSeqLessThanOrderBySeqDesc(
            String conversationId,
            int beforeSeq,
            Limit limit
    );
}
