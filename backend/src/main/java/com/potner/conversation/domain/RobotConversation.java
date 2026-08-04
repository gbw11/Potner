package com.potner.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 로봇과 사용자가 나눈 대화 한 갈래다.
 *
 * <p>젯슨의 {@code conversation.session_id} 하나가 한 갈래이며, 폰 음성과 모니터 타이핑이 같은
 * 값을 쓰면 한 갈래로 이어진다. 재접속마다 새로 만들지 않는다 — 그러면 이력이 끊겨 "아까 내가
 * 뭐라고 했지" 에 답할 수 없다.
 *
 * <p>{@code plantId} 는 갈래를 만든 시점의 활성 배정에서 채우고 갱신하지 않는다. 지난 대화는
 * 그때 그 식물과 나눈 것이라, 나중 배정으로 옮기면 사실이 바뀐다.
 */
@Entity
@Table(name = "robot_conversation")
public class RobotConversation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "conversation_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, columnDefinition = "char(36)")
    private String plantId;

    @Column(name = "session_key", length = 100, nullable = false)
    private String sessionKey;

    @Column(name = "started_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected RobotConversation() {
    }

    public static RobotConversation open(String robotId, String plantId, String sessionKey) {
        RobotConversation conversation = new RobotConversation();
        conversation.id = UUID.randomUUID().toString();
        conversation.robotId = robotId;
        conversation.plantId = plantId;
        conversation.sessionKey = sessionKey;
        return conversation;
    }

    public String getId() {
        return id;
    }

    public String getRobotId() {
        return robotId;
    }

    public String getPlantId() {
        return plantId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
