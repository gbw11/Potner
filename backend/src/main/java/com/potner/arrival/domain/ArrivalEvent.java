package com.potner.arrival.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "arrival_event")
public class ArrivalEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "visit_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String visitId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @Column(name = "device_uid", length = 100, nullable = false)
    private String deviceUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 20, nullable = false)
    private ArrivalEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30, nullable = false)
    private ArrivalEventSource source;

    @Column(name = "geofence_id", length = 50)
    private String geofenceId;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", length = 30, nullable = false)
    private ArrivalProcessingStatus processingStatus;

    @Column(name = "result_message_id", length = 100)
    private String resultMessageId;

    @Column(name = "error_message", length = 200)
    private String errorMessage;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ArrivalEvent() {
    }

    public static ArrivalEvent received(
            String eventId,
            String visitId,
            String userId,
            String robotId,
            String deviceUid,
            ArrivalEventType eventType,
            ArrivalEventSource source,
            String geofenceId,
            LocalDateTime occurredAt
    ) {
        ArrivalEvent event = new ArrivalEvent();
        event.eventId = eventId;
        event.visitId = visitId;
        event.userId = userId;
        event.robotId = robotId;
        event.deviceUid = deviceUid;
        event.eventType = eventType;
        event.source = source;
        event.geofenceId = geofenceId;
        event.occurredAt = occurredAt;
        event.processingStatus = ArrivalProcessingStatus.COMMAND_PUBLISHED;
        return event;
    }

    public void applyResult(
            ArrivalProcessingStatus status,
            String errorMessage,
            String resultMessageId,
            LocalDateTime reportedAt
    ) {
        this.processingStatus = status;
        this.errorMessage = truncate(errorMessage);
        this.resultMessageId = resultMessageId;
        this.reportedAt = reportedAt;
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 200) {
            return message;
        }
        return message.substring(0, 200);
    }

    public String getEventId() {
        return eventId;
    }

    public String getVisitId() {
        return visitId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRobotId() {
        return robotId;
    }

    public String getDeviceUid() {
        return deviceUid;
    }

    public ArrivalEventType getEventType() {
        return eventType;
    }

    public ArrivalEventSource getSource() {
        return source;
    }

    public String getGeofenceId() {
        return geofenceId;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public ArrivalProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public String getResultMessageId() {
        return resultMessageId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
