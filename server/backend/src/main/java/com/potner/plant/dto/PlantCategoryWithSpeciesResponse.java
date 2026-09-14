package com.potner.plant.dto;

import java.util.List;

/** 대분류 하나와 그 아래 선택 가능한 종 목록이다. 종마다 지원 자람 수준을 함께 담는다. */
public record PlantCategoryWithSpeciesResponse(
        String categoryId,
        String name,
        int sortOrder,
        List<PlantSpeciesWithStagesResponse> species
) {
    public PlantCategoryWithSpeciesResponse {
        species = List.copyOf(species);
    }
}
