package com.potner.plant.dto;

import com.potner.photo.dto.PhotoResponse;
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
        boolean customized,
        /** 목록 썸네일용이다. 지정하지 않았으면 null 이다. */
        PhotoResponse representativePhoto
) {
    public static PlantSummaryResponse from(
            Plant plant,
            boolean customized,
            PhotoResponse representativePhoto
    ) {
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
                customized,
                representativePhoto
        );
    }
}
