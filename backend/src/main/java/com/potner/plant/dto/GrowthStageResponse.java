package com.potner.plant.dto;

import com.potner.plant.domain.PlantLifeStage;

public record GrowthStageResponse(
        String lifeStageId,
        String code,
        String name,
        String description,
        int sortOrder
) {
    public static GrowthStageResponse from(PlantLifeStage lifeStage) {
        return new GrowthStageResponse(
                lifeStage.getId(),
                lifeStage.getCode(),
                lifeStage.getName(),
                lifeStage.getDescription(),
                lifeStage.getSortOrder()
        );
    }
}
