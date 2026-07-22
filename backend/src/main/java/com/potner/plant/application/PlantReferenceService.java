package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.PlantSpeciesRepository;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.plant.dto.GrowthRequirementResponse;
import com.potner.plant.dto.GrowthStageListResponse;
import com.potner.plant.dto.GrowthStageResponse;
import com.potner.plant.dto.PlantSpeciesListResponse;
import com.potner.plant.dto.PlantSpeciesResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class PlantReferenceService {

    private final PlantSpeciesRepository plantSpeciesRepository;
    private final SpeciesGrowthRequirementRepository requirementRepository;

    public PlantReferenceService(
            PlantSpeciesRepository plantSpeciesRepository,
            SpeciesGrowthRequirementRepository requirementRepository
    ) {
        this.plantSpeciesRepository = plantSpeciesRepository;
        this.requirementRepository = requirementRepository;
    }

    public PlantSpeciesListResponse getActiveSpecies() {
        List<PlantSpeciesResponse> species = plantSpeciesRepository.findAllByActiveTrueOrderByNameAsc()
                .stream()
                .map(PlantSpeciesResponse::from)
                .toList();
        return new PlantSpeciesListResponse(species);
    }

    public GrowthStageListResponse getAvailableGrowthStages(String speciesId) {
        PlantSpecies species = findActiveSpecies(speciesId);
        List<GrowthStageResponse> growthStages = requirementRepository.findAvailableLifeStages(speciesId)
                .stream()
                .map(GrowthStageResponse::from)
                .toList();
        return new GrowthStageListResponse(species.getId(), species.getName(), growthStages);
    }

    public GrowthRequirementResponse getGrowthRequirement(String speciesId, String lifeStageId) {
        findActiveSpecies(speciesId);
        SpeciesGrowthRequirement requirement = requirementRepository
                .findActiveRequirement(speciesId, lifeStageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROWTH_REQUIREMENT_NOT_FOUND));
        return GrowthRequirementResponse.from(requirement);
    }

    private PlantSpecies findActiveSpecies(String speciesId) {
        return plantSpeciesRepository.findByIdAndActiveTrue(speciesId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_SPECIES_NOT_FOUND));
    }
}
