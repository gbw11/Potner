package com.potner.alert.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    Optional<Alert> findByPlantIdAndMetricTypeAndResolvedAtIsNull(
            String plantId,
            AlertMetricType metricType
    );

    Optional<Alert> findByIdAndUserId(String id, String userId);

    /**
     * 가장 최근에 해제된 알림이다. 배수트레이가 "마지막으로 비운 시각" 을 이 값으로 얻는다 —
     * 비움 기록 테이블을 따로 두지 않고 해제 시각을 그대로 쓴다.
     */
    Optional<Alert> findFirstByPlantIdAndMetricTypeAndResolvedAtIsNotNullOrderByResolvedAtDesc(
            String plantId,
            AlertMetricType metricType
    );

    /**
     * 그날 어떤 이상이 있었는지 모을 때 쓴다. 일기 생성이 근거로 삼는다.
     *
     * <p>{@code occurredAt} 은 UTC 라 서비스 타임존 하루의 경계를 호출자가 계산해 넘긴다.
     */
    List<Alert> findAllByPlantIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            String plantId,
            LocalDateTime fromInclusive,
            LocalDateTime toInclusive
    );

    long countByUserIdAndReadAtIsNull(String userId);

    /**
     * 사용자 알림 목록이다. 발생 시각이 아니라 알림이 만들어진 시각 기준 최신순이며,
     * 같은 초에 만들어진 알림이 섞이지 않도록 식별자로 순서를 확정한다.
     */
    @Query(value = """
            select a
            from Alert a
            where a.userId = :userId
              and (:unreadOnly = false or a.readAt is null)
              and (:activeOnly = false or a.resolvedAt is null)
            order by a.createdAt desc, a.id desc
            """,
            countQuery = """
            select count(a)
            from Alert a
            where a.userId = :userId
              and (:unreadOnly = false or a.readAt is null)
              and (:activeOnly = false or a.resolvedAt is null)
            """)
    Page<Alert> findOwned(
            @Param("userId") String userId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("activeOnly") boolean activeOnly,
            Pageable pageable
    );
}
