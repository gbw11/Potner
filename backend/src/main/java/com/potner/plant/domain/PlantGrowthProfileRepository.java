package com.potner.plant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlantGrowthProfileRepository extends JpaRepository<PlantGrowthProfile, String> {

    Optional<PlantGrowthProfile> findByPlantId(String plantId);

    List<PlantGrowthProfile> findAllByPlantIdIn(Collection<String> plantIds);
}
