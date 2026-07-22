package com.potner.plant.dto;

import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;

import java.time.LocalDateTime;

public record PlantDetailResponse(
        String plantId,
        String name,
        PlantSpeciesResponse species,
        PlantCategoryResponse category,
        GrowthStageResponse lifeStage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        GrowthProfileResponse growthProfile
) {
    public static PlantDetailResponse from(Plant plant, PlantGrowthProfile profile) {
        return new PlantDetailResponse(
                plant.getId(),
                plant.getNickname(),
                PlantSpeciesResponse.from(plant.getSpecies()),
                PlantCategoryResponse.from(plant.getSpecies().getCategory()),
                GrowthStageResponse.from(plant.getLifeStage()),
                plant.getCreatedAt(),
                plant.getUpdatedAt(),
                GrowthProfileResponse.from(profile, plant.getLifeStage())
        );
    }
}
