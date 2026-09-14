package com.potner.plant.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlantRepository extends JpaRepository<Plant, String> {

    @EntityGraph(attributePaths = {"species", "species.category", "lifeStage"})
    @Query("""
            select p
            from Plant p
            where p.user.id = :userId
              and p.status <> :excludedStatus
            order by p.createdAt desc, p.id desc
            """)
    List<Plant> findAllOwnedNotDeleted(
            @Param("userId") String userId,
            @Param("excludedStatus") PlantStatus excludedStatus
    );

    @EntityGraph(attributePaths = {"species", "species.category", "lifeStage"})
    Optional<Plant> findByIdAndUserIdAndStatusNot(String id, String userId, PlantStatus excludedStatus);

    /**
     * 소유자를 묻지 않고 찾는다. 사진 판정처럼 사용자 요청이 아닌 경로가 쓴다.
     *
     * <p>사용자 요청 경로에서는 쓰지 말아야 한다. 소유권 검증이 빠져 있어 남의 식물에 닿는다.
     * 그래서 이름에 {@code UserId} 가 없는 것 자체가 신호가 되도록 두었다.
     *
     * <p>{@code user} 를 그래프에 넣는다. 판정 결과로 푸시를 보낼 때 사용자 식별자가 필요한데,
     * 수신자는 커밋 뒤 다른 스레드라 그때 지연 로딩을 시도하면 영속성 컨텍스트가 이미 닫혀 있다.
     */
    @EntityGraph(attributePaths = {"user", "species", "lifeStage"})
    Optional<Plant> findByIdAndStatusNot(String id, PlantStatus excludedStatus);

    /** 일일 집계 대상. 연관 데이터가 필요하지 않으므로 그래프를 붙이지 않는다. */
    List<Plant> findAllByStatus(PlantStatus status);
}
