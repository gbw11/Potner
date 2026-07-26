package com.potner.light.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlantDailyLightRepository extends JpaRepository<PlantDailyLight, String> {

    Optional<PlantDailyLight> findByPlantIdAndLightDate(String plantId, LocalDate lightDate);

    List<PlantDailyLight> findByPlantIdAndLightDateBetweenOrderByLightDateDesc(
            String plantId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    );
}
