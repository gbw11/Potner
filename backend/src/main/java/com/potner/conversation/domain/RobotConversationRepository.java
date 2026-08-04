package com.potner.conversation.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RobotConversationRepository extends JpaRepository<RobotConversation, String> {

    /** 장치가 발화를 올릴 때 갈래를 찾는다. 없으면 첫 발화에서 만든다. */
    Optional<RobotConversation> findByRobotIdAndSessionKey(String robotId, String sessionKey);

    /**
     * 앱이 식물의 대화를 볼 때 쓴다. 한 식물에 갈래가 여럿일 수 있으므로(기기 교체, 세션 분리)
     * 가장 최근에 오간 것을 고른다.
     */
    Optional<RobotConversation> findFirstByPlantIdOrderByUpdatedAtDesc(String plantId);
}
