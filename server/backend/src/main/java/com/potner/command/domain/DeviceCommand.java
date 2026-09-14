package com.potner.command.domain;

import com.potner.location.domain.RobotLocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 서버가 장치에 보낸 명령 한 건이다.
 *
 * <p>{@code requestId} 가 PK 이자 매칭 열쇠다. 서버가 발급해 페이로드에 싣고, 장치는 회신에
 * 그대로 되돌린다. 라즈베리 수신기가 이 반향을 이미 구현했다.
 *
 * <p>{@code deviceUid} 를 비정규화해 둔다. 회신 토픽의 세그먼트와 대조해야 하는데, iot_device
 * 를 조인하면 회신 처리가 매번 두 테이블을 탄다. 명령을 받은 장치는 바뀌지 않으므로 안전하다.
 */
@Entity
@Table(name = "device_command")
public class DeviceCommand {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String requestId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @Column(name = "device_uid", length = 100, nullable = false)
    private String deviceUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", length = 10, nullable = false)
    private DeviceCommandType commandType;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiator", length = 10, nullable = false)
    private CommandInitiator initiator;

    /** 자동 체인 구분이다. 사용자 명령은 {@code null}. DB CHECK 가 그 짝을 강제한다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 20)
    private CommandPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 10, nullable = false)
    private DeviceCommandStatus status;

    @Column(name = "requested_ml", precision = 10, scale = 2)
    private BigDecimal requestedMl;

    /** NAVIGATE 만. 목적지 위치 종류다. 좌표는 발행 시점의 robot_location 에서 꺼내 실었다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "destination", length = 20)
    private RobotLocationType destination;

    /**
     * FAN 만. 1회 가동 시간(초)이다.
     *
     * <p>회신 전에는 서버가 요청한 값이고, 회신이 오면 <strong>장치가 실제로 돌린 시간으로
     * 덮어쓴다.</strong> 라즈베리는 풍량·시간을 자기 설정으로 정하고 요청값을 참고만 하므로,
     * 요청값을 그대로 남기면 이력이 실제와 다르게 기록된다.
     *
     * <p>급수처럼 요청·실측 컬럼을 나누지 않는 이유는 요청값에 남길 정보가 없기 때문이다.
     * 급수량은 서버가 생육 기준에서 정하는 의미 있는 값이지만, 가동 시간은 장치가 정한다.
     */
    @Column(name = "run_seconds")
    private Integer runSeconds;

    @Column(name = "dispensed_ml", precision = 10, scale = 2)
    private BigDecimal dispensedMl;

    @Column(name = "error_message", length = 200)
    private String errorMessage;

    @Column(name = "result_message_id", length = 100)
    private String resultMessageId;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "reported_at")
    private LocalDateTime reportedAt;

    protected DeviceCommand() {
    }

    public static DeviceCommand issue(
            String plantId,
            String robotId,
            String deviceUid,
            DeviceCommandType commandType,
            CommandInitiator initiator,
            CommandPurpose purpose,
            BigDecimal requestedMl,
            RobotLocationType destination,
            Integer runSeconds,
            LocalDateTime issuedAt
    ) {
        DeviceCommand command = new DeviceCommand();
        command.requestId = UUID.randomUUID().toString();
        command.plantId = plantId;
        command.robotId = robotId;
        command.deviceUid = deviceUid;
        command.commandType = commandType;
        command.initiator = initiator;
        command.purpose = purpose;
        command.status = DeviceCommandStatus.ISSUED;
        command.requestedMl = requestedMl;
        command.destination = destination;
        command.runSeconds = runSeconds;
        command.issuedAt = issuedAt;
        return command;
    }

    /**
     * 장치 회신을 반영한다.
     *
     * <p>{@code TIMED_OUT} 위에도 덮어쓴다. 타임아웃은 서버의 추정이고 회신은 물리적 사실이라
     * 늦게 온 회신이 이긴다 — 특히 실제 급수량은 일기에 실리므로 버리면 기록이 틀어진다.
     * 중복 회신 여부는 서비스가 {@code resultMessageId} 로 먼저 거른다.
     */
    public void applyResult(
            DeviceCommandStatus reported,
            BigDecimal dispensedMl,
            Integer reportedRunSeconds,
            String errorMessage,
            String resultMessageId,
            LocalDateTime reportedAt
    ) {
        this.status = reported;
        this.dispensedMl = dispensedMl;
        if (reportedRunSeconds != null) {
            this.runSeconds = reportedRunSeconds;
        }
        this.errorMessage = truncate(errorMessage);
        this.resultMessageId = resultMessageId;
        this.reportedAt = reportedAt;
    }

    /** 장치 회신 문구는 길이를 통제할 수 없다. 컬럼 상한에 맞춰 자른다. */
    private static String truncate(String message) {
        if (message == null || message.length() <= 200) {
            return message;
        }
        return message.substring(0, 200);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPlantId() {
        return plantId;
    }

    public String getRobotId() {
        return robotId;
    }

    public String getDeviceUid() {
        return deviceUid;
    }

    public CommandInitiator getInitiator() {
        return initiator;
    }

    public CommandPurpose getPurpose() {
        return purpose;
    }

    public DeviceCommandType getCommandType() {
        return commandType;
    }

    public DeviceCommandStatus getStatus() {
        return status;
    }

    public BigDecimal getRequestedMl() {
        return requestedMl;
    }

    public RobotLocationType getDestination() {
        return destination;
    }

    public Integer getRunSeconds() {
        return runSeconds;
    }

    public BigDecimal getDispensedMl() {
        return dispensedMl;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getResultMessageId() {
        return resultMessageId;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getReportedAt() {
        return reportedAt;
    }
}
