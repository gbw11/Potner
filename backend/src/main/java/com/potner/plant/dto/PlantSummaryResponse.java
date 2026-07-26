package com.potner.plant.dto;

import com.potner.plant.domain.Plant;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record PlantSummaryResponse(
        String plantId,
        String name,
        LocalDate adoptedDate,
        String speciesId,
        String speciesName,
        String scientificName,
        String categoryId,
        String categoryName,
        String lifeStageId,
        String lifeStageCode,
        String lifeStageName,
        LocalDateTime createdAt,
        boolean customized
) {
    public static PlantSummaryResponse from(Plant plant, boolean customized) {
        return new PlantSummaryResponse(
                plant.getId(),
                plant.getNickname(),
                plant.getAdoptedDate(),
                plant.getSpecies().getId(),
                plant.getSpecies().getName(),
                plant.getSpecies().getScientificName(),
                plant.getSpecies().getCategory().getId(),
                plant.getSpecies().getCategory().getName(),
                plant.getLifeStage().getId(),
                plant.getLifeStage().getCode(),
                plant.getLifeStage().getName(),
                plant.getCreatedAt(),
                customized
        );
    }
}
