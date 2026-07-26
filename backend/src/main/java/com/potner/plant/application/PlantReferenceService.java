package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.PlantCategory;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.PlantSpeciesRepository;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.plant.dto.GrowthRequirementResponse;
import com.potner.plant.dto.GrowthStageListResponse;
import com.potner.plant.dto.GrowthStageResponse;
import com.potner.plant.dto.PlantCategoryTreeResponse;
import com.potner.plant.dto.PlantCategoryWithSpeciesResponse;
import com.potner.plant.dto.PlantSpeciesListResponse;
import com.potner.plant.dto.PlantSpeciesResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 등록 화면의 대분류·소분류 선택용 계층이다.
     *
     * <p>루트 분류는 노출하지 않고, 선택 가능한 종이 없는 분류도 빼서 빈 드롭다운이 생기지 않게 한다.
     * 데이터가 작아 한 번의 조회로 두 드롭다운을 모두 채운다.
     */
    public PlantCategoryTreeResponse getCategoryTree() {
        Map<String, PlantCategory> categories = new LinkedHashMap<>();
        Map<String, List<PlantSpeciesResponse>> speciesByCategory = new LinkedHashMap<>();

        for (PlantSpecies species : plantSpeciesRepository
                .findAllByActiveTrueOrderByCategorySortOrderAscNameAsc()) {
            PlantCategory category = species.getCategory();
            if (category == null || !category.isActive()) {
                continue;
            }
            categories.putIfAbsent(category.getId(), category);
            speciesByCategory
                    .computeIfAbsent(category.getId(), key -> new ArrayList<>())
                    .add(PlantSpeciesResponse.from(species));
        }

        List<PlantCategoryWithSpeciesResponse> tree = categories.values().stream()
                .map(category -> new PlantCategoryWithSpeciesResponse(
                        category.getId(),
                        category.getName(),
                        category.getSortOrder(),
                        speciesByCategory.get(category.getId())))
                .toList();
        return new PlantCategoryTreeResponse(tree);
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
