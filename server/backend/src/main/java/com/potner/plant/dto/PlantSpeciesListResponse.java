package com.potner.plant.dto;

import java.util.List;

public record PlantSpeciesListResponse(List<PlantSpeciesResponse> species) {
    public PlantSpeciesListResponse {
        species = List.copyOf(species);
    }
}
