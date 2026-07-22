package com.potner.plant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantSpeciesRepository extends JpaRepository<PlantSpecies, String> {

    List<PlantSpecies> findAllByActiveTrueOrderByNameAsc();

    Optional<PlantSpecies> findByIdAndActiveTrue(String id);
}
