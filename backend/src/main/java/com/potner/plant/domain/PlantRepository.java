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
}
