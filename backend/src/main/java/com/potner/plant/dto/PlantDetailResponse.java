package com.potner.plant.dto;

import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PlantDetailResponse(
        String plantId,
        String name,
        /** 데려온 날짜. 나이는 사용자 기기의 오늘을 기준으로 앱에서 계산한다. */
        LocalDate adoptedDate,
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
                plant.getAdoptedDate(),
                PlantSpeciesResponse.from(plant.getSpecies()),
                PlantCategoryResponse.from(plant.getSpecies().getCategory()),
                GrowthStageResponse.from(plant.getLifeStage()),
                plant.getCreatedAt(),
                plant.getUpdatedAt(),
                GrowthProfileResponse.from(profile, plant.getLifeStage())
        );
    }
}
