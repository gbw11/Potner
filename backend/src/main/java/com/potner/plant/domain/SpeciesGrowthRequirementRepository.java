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
