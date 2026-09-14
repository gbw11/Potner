package com.potner.arrival.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ArrivalEventRepository extends JpaRepository<ArrivalEvent, String> {

    Optional<ArrivalEvent> findByEventIdAndUserId(String eventId, String userId);

    boolean existsByVisitIdAndUserIdAndEventType(
            String visitId,
            String userId,
            ArrivalEventType eventType
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ArrivalEvent event
               SET event.processingStatus = :timedOut
             WHERE event.processingStatus = :published
               AND event.createdAt < :cutoff
            """)
    int markPublishedTimedOutBefore(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("published") ArrivalProcessingStatus published,
            @Param("timedOut") ArrivalProcessingStatus timedOut
    );
}
