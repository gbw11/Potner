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
import com.potner.plant.dto.PlantSpeciesWithStagesResponse;
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
     * 등록 화면의 대분류·소분류·자람 수준 선택용 계층이다.
     *
     * <p>루트 분류는 노출하지 않고, 선택 가능한 종이 없는 분류도 빼서 빈 드롭다운이 생기지 않게 한다.
     * 데이터가 작아 두 번의 조회로 세 드롭다운을 모두 채운다.
     *
     * <p>자람 수준을 여기 함께 싣는 이유는 앱이 종을 고를 때마다 추가 조회를 하지 않게 하기 위함이다.
     * 자람 수준은 종마다 다르므로 앱이 목록을 하드코딩하면 안 된다.
     */
    public PlantCategoryTreeResponse getCategoryTree() {
        Map<String, List<GrowthStageResponse>> stagesBySpecies = availableStagesBySpecies();
        Map<String, PlantCategory> categories = new LinkedHashMap<>();
        Map<String, List<PlantSpeciesWithStagesResponse>> speciesByCategory = new LinkedHashMap<>();

        for (PlantSpecies species : plantSpeciesRepository
                .findAllByActiveTrueOrderByCategorySortOrderAscNameAsc()) {
            PlantCategory category = species.getCategory();
            if (category == null || !category.isActive()) {
                continue;
            }
            // 자람 수준이 없는 종은 고르면 복사할 생육 기준이 없어 등록이 실패한다.
            // 빈 분류를 빼는 것과 같은 이유로 선택지에서 제외한다.
            List<GrowthStageResponse> growthStages = stagesBySpecies.get(species.getId());
            if (growthStages == null || growthStages.isEmpty()) {
                continue;
            }
            categories.putIfAbsent(category.getId(), category);
            speciesByCategory
                    .computeIfAbsent(category.getId(), key -> new ArrayList<>())
                    .add(PlantSpeciesWithStagesResponse.of(species, growthStages));
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

    /** 종 하나당 자람 수준 여러 개다. 종 수만큼 조회하지 않도록 한 번에 가져와 묶는다. */
    private Map<String, List<GrowthStageResponse>> availableStagesBySpecies() {
        Map<String, List<GrowthStageResponse>> stagesBySpecies = new LinkedHashMap<>();
        for (SpeciesGrowthRequirement requirement : requirementRepository.findAllActiveRequirements()) {
            stagesBySpecies
                    .computeIfAbsent(requirement.getSpecies().getId(), key -> new ArrayList<>())
                    .add(GrowthStageResponse.from(requirement.getLifeStage()));
        }
        return stagesBySpecies;
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
