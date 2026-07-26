package com.potner.plant.dto;

import java.util.List;

/** 대분류 하나와 그 아래 선택 가능한 종 목록이다. */
public record PlantCategoryWithSpeciesResponse(
        String categoryId,
        String name,
        int sortOrder,
        List<PlantSpeciesResponse> species
) {
    public PlantCategoryWithSpeciesResponse {
        species = List.copyOf(species);
    }
}
