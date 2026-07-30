package com.potner.command.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, String> {

    List<DeviceCommand> findAllByPlantIdAndIssuedAtBetweenOrderByIssuedAtDesc(
            String plantId,
            LocalDateTime from,
            LocalDateTime to
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
}
