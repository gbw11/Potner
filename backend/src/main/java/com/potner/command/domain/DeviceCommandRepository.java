package com.potner.command.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
// CommandPurpose 는 같은 패키지라 import 가 필요 없다.

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, String> {

    List<DeviceCommand> findAllByPlantIdAndIssuedAtBetweenOrderByIssuedAtDesc(
            String plantId,
            LocalDateTime from,
            LocalDateTime to
    );

    /**
     * 마지막으로 성공한 이동 명령이다. 자동 재배치가 로봇의 현재 위치를 추정하는 근거다 —
     * 로봇은 위치를 보고하지 않으므로, 마지막으로 도착 확인된 목적지가 최선의 추정이다.
     */
    Optional<DeviceCommand> findFirstByPlantIdAndCommandTypeAndStatusOrderByIssuedAtDesc(
            String plantId,
            DeviceCommandType commandType,
            DeviceCommandStatus status
    );

    /** 자동 말리기의 하루 가동 상한을 센다. 이력을 세므로 서버가 재시작해도 상한이 유지된다. */
    long countByPlantIdAndCommandTypeAndPurposeAndIssuedAtAfter(
            String plantId,
            DeviceCommandType commandType,
            CommandPurpose purpose,
            LocalDateTime since
    );

    /**
     * 마지막 송풍 시각이다. 주기 환기가 간격이 찼는지 보는 근거다.
     *
     * <p><strong>목적을 가리지 않는다.</strong> 급수 체인이 방금 팬을 돌렸으면 주기 환기도
     * 쉬어야 한다 — 같은 팬 하나를 두 체인이 나눠 쓰므로, 목적별로 세면 급수한 날 송풍이
     * 두 배가 된다.
     *
     * <p>상태도 가리지 않는다. 발행했다는 사실만으로 간격을 세야 회신이 늦거나 실패한 명령
     * 뒤에 곧바로 다시 돌리는 일이 없다.
     */
    Optional<DeviceCommand> findFirstByPlantIdAndCommandTypeOrderByIssuedAtDesc(
            String plantId,
            DeviceCommandType commandType
    );

    /** 하루 송풍 상한을 센다. 목적을 가리지 않는 이유는 위와 같다. */
    long countByPlantIdAndCommandTypeAndIssuedAtAfter(
            String plantId,
            DeviceCommandType commandType,
            LocalDateTime since
    );

    /**
     * 최근 이 체인이 건드린 식물들이다.
     *
     * <p>말리기가 끝난 로봇을 대기 장소로 되돌릴 때 쓴다. 과습이 풀리면 알림이 닫히는데, 해제는
     * 이벤트를 내지 않으므로 "이제 정상인데 아직 스테이션에 있는" 로봇을 이 목록에서 찾는다.
     */
    @Query("""
            SELECT DISTINCT c.plantId
              FROM DeviceCommand c
             WHERE c.purpose = :purpose
               AND c.issuedAt > :since
            """)
    List<String> findPlantIdsByPurposeSince(
            @Param("purpose") CommandPurpose purpose,
            @Param("since") LocalDateTime since
    );

    /**
     * 같은 식물에 같은 종류의 명령이 회신 대기 중인지 본다.
     *
     * <p>대기 중에 또 발행하면 장치가 어차피 BUSY 로 거절하므로, 서버에서 먼저 막아 사용자에게
     * 명확한 응답(409)을 준다. 조용히 발행하고 BUSY 행을 남기면 이력만 지저분해진다.
     */
    boolean existsByPlantIdAndCommandTypeAndStatus(
            String plantId,
            DeviceCommandType commandType,
            DeviceCommandStatus status
    );

    /**
     * 회신이 오지 않은 채 제한 시간을 넘긴 명령을 한 번에 끊는다.
     *
     * <p>{@code reportedAt} 은 건드리지 않는다. 아무것도 보고되지 않았다는 사실 자체가 정보다.
     * 늦게 온 회신은 {@code TIMED_OUT} 위에 덮어써 물리적 사실을 남긴다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE DeviceCommand c
               SET c.status = :timedOut
             WHERE c.status = :issued
               AND c.issuedAt < :cutoff
            """)
    int markIssuedTimedOutBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("issued") DeviceCommandStatus issued,
            @Param("timedOut") DeviceCommandStatus timedOut
    );

    /**
     * 구간 내 실제 급수량 합계다. 상태 리포트의 "그날 급수량" 이 이 값이다.
     *
     * <p>상태가 아니라 {@code dispensedMl} 존재로 거른다. 펌프 상한에 걸려 ERROR 로 끝난
     * 회신에도 부분 급수량이 실릴 수 있고, 물은 이미 나갔으므로 기록에 넣어야 한다.
     *
     * @return 급수 기록이 없으면 {@code null}. 0 과 구분된다
     */
    @Query("""
            SELECT SUM(c.dispensedMl)
              FROM DeviceCommand c
             WHERE c.plantId = :plantId
               AND c.commandType = com.potner.command.domain.DeviceCommandType.WATER
               AND c.dispensedMl IS NOT NULL
               AND c.reportedAt BETWEEN :from AND :to
            """)
    BigDecimal sumDispensedMlBetween(
            @Param("plantId") String plantId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    /**
     * 어느 시점 이후 실제 급수량 합계다. 배수트레이 판정이 "마지막으로 비운 뒤 얼마나 줬는지"를
     * 이 값으로 본다.
     *
     * <p>{@code since} 가 없으면(한 번도 비우지 않았으면) 전체 기간을 합산해야 하므로 호출자가
     * 아주 과거 시각을 넘긴다. 상한을 두지 않는 이유는 트레이가 미래에 차는 일은 없기 때문이다.
     *
     * @return 급수 기록이 없으면 {@code null}. 0 과 구분된다
     */
    @Query("""
            SELECT SUM(c.dispensedMl)
              FROM DeviceCommand c
             WHERE c.plantId = :plantId
               AND c.commandType = com.potner.command.domain.DeviceCommandType.WATER
               AND c.dispensedMl IS NOT NULL
               AND c.reportedAt > :since
            """)
    BigDecimal sumDispensedMlAfter(
            @Param("plantId") String plantId,
            @Param("since") LocalDateTime since
    );
}
