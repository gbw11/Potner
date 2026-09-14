package com.potner.plant.dto;

import com.potner.plant.domain.PlantCategory;

public record PlantCategoryResponse(
        String categoryId,
        String name
) {
    public static PlantCategoryResponse from(PlantCategory category) {
        return new PlantCategoryResponse(category.getId(), category.getName());
    }
}
