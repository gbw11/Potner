package com.potner.bloom.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface PlantBloomRepository extends JpaRepository<PlantBloom, String> {

    Optional<PlantBloom> findByIdAndUserId(String id, String userId);

    Optional<PlantBloom> findByIdAndPlantId(String id, String plantId);

    long countByUserIdAndReadAtIsNull(String userId);

    /** 첫 개화인지 판정한다. 첫 꽃과 그 뒤의 꽃은 푸시 문구가 다르다. */
    boolean existsByPlantId(String plantId);

    /** 오늘 꽃이 폈는지 본다. 표정 판정이 쓰며 그날 하루 내내 유지된다. */
    boolean existsByPlantIdAndBloomDate(String plantId, LocalDate bloomDate);

    /** 최근 개화가 있었는지 본다. 자동 개화 기록의 냉각 기간 판정이 쓴다. 수동 기록도 센다. */
    boolean existsByPlantIdAndBloomDateGreaterThanEqual(String plantId, LocalDate fromInclusive);

    /**
     * 사용자 개화 기록 목록이다. 개화한 날 기준 최신순이며, 같은 날 두 송이가 피었을 때
     * 순서가 흔들리지 않도록 생성 시각과 식별자로 순서를 확정한다.
     */
    @Query(value = """
            select b
            from PlantBloom b
            where b.userId = :userId
              and (:unreadOnly = false or b.readAt is null)
            order by b.bloomDate desc, b.createdAt desc, b.id desc
            """,
            countQuery = """
            select count(b)
            from PlantBloom b
            where b.userId = :userId
              and (:unreadOnly = false or b.readAt is null)
            """)
    Page<PlantBloom> findOwned(
            @Param("userId") String userId,
            @Param("unreadOnly") boolean unreadOnly,
            Pageable pageable
    );
}
