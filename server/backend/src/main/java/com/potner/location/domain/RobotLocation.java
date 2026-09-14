package com.potner.location.domain;

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
 * 로봇이 오가는 지도 위 위치 한 곳이다.
 *
 * <p>등록과 좌표 입력이 분리되어 있다. 등록은 사용자가 앱에서(스테이션 코드 입력), 좌표는
 * 설치자가 RViz 에서 읽어 관리 API 로 넣는다. 그래서 좌표가 {@code null} 인 것은 정상이며
 * "아직 좌표를 안 넣었다" 를 뜻한다. 0,0,0 은 지도 원점이라는 실제 좌표라 미설정 표현으로
 * 쓰면 로봇이 원점으로 달려간다.
 */
@Entity
@Table(name = "robot_location")
public class RobotLocation {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "location_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "robot_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String robotId;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 20, nullable = false)
    private RobotLocationType locationType;

    @Column(name = "station_code", length = 50)
    private String stationCode;

    @Column(name = "pose_x", precision = 8, scale = 3)
    private BigDecimal poseX;

    @Column(name = "pose_y", precision = 8, scale = 3)
    private BigDecimal poseY;

    @Column(name = "pose_yaw", precision = 6, scale = 4)
    private BigDecimal poseYaw;

    /** 물 부족 보고다. 갱신 경로는 스테이션 상태 수신 작업에서 붙는다. */
    @Column(name = "water_low", nullable = false)
    private boolean waterLow;

    @Column(name = "water_low_at")
    private LocalDateTime waterLowAt;

    protected RobotLocation() {
    }

    public static RobotLocation register(
            String robotId,
            RobotLocationType locationType,
            String stationCode
    ) {
        RobotLocation location = new RobotLocation();
        location.id = UUID.randomUUID().toString();
        location.robotId = robotId;
        location.locationType = locationType;
        location.stationCode = stationCode;
        location.waterLow = false;
        return location;
    }

    /**
     * 좌표를 통째로 바꾼다. 지도를 다시 그리면 세 값이 모두 무효가 되므로 부분 수정은 없다.
     */
    public void updatePose(BigDecimal x, BigDecimal y, BigDecimal yaw) {
        this.poseX = x;
        this.poseY = y;
        this.poseYaw = yaw;
    }

    /** 좌표가 들어왔는지. 미설정 위치로는 로봇을 보낼 수 없다. */
    public boolean hasPose() {
        return poseX != null && poseY != null && poseYaw != null;
    }

    /**
     * 물 부족 보고를 반영한다.
     *
     * <p>부족일 때만 보고 시각을 갱신한다. {@code waterLowAt} 은 "마지막으로 부족이 보고된
     * 시각" 이고, 해제 뒤에도 남겨 두어 언제까지 부족했는지가 보이게 한다.
     *
     * @return 부족 상태로 <strong>처음</strong> 바뀌었으면 {@code true}. 알림은 이때만 나간다 —
     *         장치가 같은 상태를 주기적으로 반복 보고하므로 매번 알리면 물을 채울 때까지
     *         알림이 쏟아진다
     */
    public boolean reportWaterLow(boolean low, LocalDateTime reportedAt) {
        boolean becameLow = low && !this.waterLow;
        this.waterLow = low;
        if (low) {
            this.waterLowAt = reportedAt;
        }
        return becameLow;
    }

    public String getId() {
        return id;
    }

    public String getRobotId() {
        return robotId;
    }

    public RobotLocationType getLocationType() {
        return locationType;
    }

    public String getStationCode() {
        return stationCode;
    }

    public BigDecimal getPoseX() {
        return poseX;
    }

    public BigDecimal getPoseY() {
        return poseY;
    }

    public BigDecimal getPoseYaw() {
        return poseYaw;
    }

    public boolean isWaterLow() {
        return waterLow;
    }

    public LocalDateTime getWaterLowAt() {
        return waterLowAt;
    }
}
