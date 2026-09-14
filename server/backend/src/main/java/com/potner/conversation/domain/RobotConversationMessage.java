package com.potner.conversation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 대화의 한 발화다.
 *
 * <p>{@code seq} 는 갈래 안에서 0 부터 늘어나는 순서다. {@code createdAt} 만으로는 같은 초에
 * 들어온 user/assistant 짝의 순서가 정해지지 않는다. 앱 스크롤백의 커서로도 쓴다.
 */
@Entity
@Table(name = "robot_conversation_message")
public class RobotConversationMessage {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "message_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "conversation_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String conversationId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private ConversationRole role;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected RobotConversationMessage() {
    }

    public static RobotConversationMessage of(
            String conversationId,
            int seq,
            ConversationRole role,
            String content
    ) {
        RobotConversationMessage message = new RobotConversationMessage();
        message.id = UUID.randomUUID().toString();
        message.conversationId = conversationId;
        message.seq = seq;
        message.role = role;
        message.content = content;
        return message;
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public int getSeq() {
        return seq;
    }

    public ConversationRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
