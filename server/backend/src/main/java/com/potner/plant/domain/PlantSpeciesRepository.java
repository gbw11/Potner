package com.potner.plant.domain;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantSpeciesRepository extends JpaRepository<PlantSpecies, String> {

    List<PlantSpecies> findAllByActiveTrueOrderByNameAsc();

    /** 대분류 정렬 순서대로 종을 가져온다. 등록 화면의 계층 목록을 한 번의 조회로 만든다. */
    @EntityGraph(attributePaths = "category")
    List<PlantSpecies> findAllByActiveTrueOrderByCategorySortOrderAscNameAsc();

    Optional<PlantSpecies> findByIdAndActiveTrue(String id);
}
