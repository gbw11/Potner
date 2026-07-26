package com.potner.alert.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    Optional<Alert> findByPlantIdAndMetricTypeAndResolvedAtIsNull(
            String plantId,
            AlertMetricType metricType
    );

    Optional<Alert> findByIdAndUserId(String id, String userId);

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
