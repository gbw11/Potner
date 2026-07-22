package com.potner.plant.dto;

import com.potner.plant.domain.PlantSpecies;

public record PlantSpeciesResponse(
        String speciesId,
        String name,
        String scientificName,
        String description
) {
    public static PlantSpeciesResponse from(PlantSpecies species) {
        return new PlantSpeciesResponse(
                species.getId(),
                species.getName(),
                species.getScientificName(),
                species.getDescription()
        );
    }
}
