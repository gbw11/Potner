package com.potner.plant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlantLifeStageRepository extends JpaRepository<PlantLifeStage, String> {

    Optional<PlantLifeStage> findByIdAndActiveTrue(String id);
}
