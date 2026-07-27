package com.potner.plant.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpeciesGrowthRequirementRepository extends JpaRepository<SpeciesGrowthRequirement, String> {

    @Query("""
            select r.lifeStage
            from SpeciesGrowthRequirement r
            where r.species.id = :speciesId
              and r.active = true
              and r.lifeStage.active = true
            order by r.lifeStage.sortOrder asc, r.lifeStage.code asc
            """)
    List<PlantLifeStage> findAvailableLifeStages(@Param("speciesId") String speciesId);

    /**
     * 활성 종 전체의 선택 가능한 자람 수준을 한 번에 가져온다.
     *
     * <p>등록 화면이 종을 고를 때마다 추가 조회를 하지 않도록 분류 계층 응답에 함께 싣기 위한 것이다.
     * 정렬은 {@link #findAvailableLifeStages}와 같아야 두 API의 자람 수준 순서가 어긋나지 않는다.
     */
    @Query("""
            select r
            from SpeciesGrowthRequirement r
            join fetch r.species s
            join fetch r.lifeStage ls
            where r.active = true
              and s.active = true
              and ls.active = true
            order by ls.sortOrder asc, ls.code asc
            """)
    List<SpeciesGrowthRequirement> findAllActiveRequirements();

    @Query("""
            select r
            from SpeciesGrowthRequirement r
            join fetch r.species s
            join fetch r.lifeStage ls
            where s.id = :speciesId
              and ls.id = :lifeStageId
              and s.active = true
              and ls.active = true
              and r.active = true
            """)
    Optional<SpeciesGrowthRequirement> findActiveRequirement(
            @Param("speciesId") String speciesId,
            @Param("lifeStageId") String lifeStageId
    );
}
