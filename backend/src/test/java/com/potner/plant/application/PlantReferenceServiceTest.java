package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.PlantSpeciesRepository;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.plant.dto.GrowthRequirementResponse;
import com.potner.plant.dto.GrowthStageListResponse;
import com.potner.plant.dto.PlantSpeciesListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlantReferenceServiceTest {

    private static final String SPECIES_ID = "20000000-0000-0000-0000-000000000104";
    private static final String STAGE_ID = "10000000-0000-0000-0000-000000000005";

    @Mock
    private PlantSpeciesRepository plantSpeciesRepository;

    @Mock
    private SpeciesGrowthRequirementRepository requirementRepository;

    private PlantReferenceService plantReferenceService;

    @BeforeEach
    void setUp() {
        plantReferenceService = new PlantReferenceService(plantSpeciesRepository, requirementRepository);
    }

    @Test
    void categoryTreeGroupsSpeciesAndDropsInactiveCategories() {
        com.potner.plant.domain.PlantCategory herb =
                mock(com.potner.plant.domain.PlantCategory.class);
        when(herb.getId()).thenReturn("category-herb");
        when(herb.getName()).thenReturn("허브");
        when(herb.getSortOrder()).thenReturn(20);
        when(herb.isActive()).thenReturn(true);
        com.potner.plant.domain.PlantCategory retired =
                mock(com.potner.plant.domain.PlantCategory.class);
        when(retired.isActive()).thenReturn(false);

        // 스텁 안에서 다시 스텁을 만들지 않도록 종 목록을 먼저 준비한다.
        // 비활성 분류에 속한 종은 빈 드롭다운을 만들지 않도록 제외되어야 한다.
        PlantSpecies basil = speciesIn(herb, "species-basil", "바질");
        PlantSpecies agastache = speciesIn(herb, "species-agastache", "배초향");
        PlantSpecies hidden = speciesIn(retired, "species-hidden", "숨김");
        when(plantSpeciesRepository.findAllByActiveTrueOrderByCategorySortOrderAscNameAsc())
                .thenReturn(List.of(basil, agastache, hidden));

        PlantLifeStage seedling = stage("stage-seedling", "SEEDLING", "유묘기", 1);
        List<SpeciesGrowthRequirement> requirements = List.of(
                requirement(basil, seedling),
                requirement(agastache, seedling),
                requirement(hidden, seedling)
        );
        when(requirementRepository.findAllActiveRequirements()).thenReturn(requirements);

        var response = plantReferenceService.getCategoryTree();

        assertThat(response.categories()).hasSize(1);
        assertThat(response.categories().getFirst().categoryId()).isEqualTo("category-herb");
        assertThat(response.categories().getFirst().name()).isEqualTo("허브");
        assertThat(response.categories().getFirst().sortOrder()).isEqualTo(20);
        assertThat(response.categories().getFirst().species())
                .extracting(species -> species.name())
                .containsExactly("바질", "배초향");
    }

    @Test
    void categoryTreeCarriesSpeciesSpecificGrowthStages() {
        // 자람 수준은 종마다 다르다. 앱이 하드코딩하지 않도록 종별로 담아 한 번에 내려준다.
        com.potner.plant.domain.PlantCategory category =
                mock(com.potner.plant.domain.PlantCategory.class);
        when(category.getId()).thenReturn("category-herb");
        when(category.isActive()).thenReturn(true);

        PlantSpecies basil = speciesIn(category, "species-basil", "바질");
        PlantSpecies tomato = speciesIn(category, "species-tomato", "방울토마토");
        when(plantSpeciesRepository.findAllByActiveTrueOrderByCategorySortOrderAscNameAsc())
                .thenReturn(List.of(basil, tomato));

        PlantLifeStage seedling = stage("stage-seedling", "SEEDLING", "유묘기", 1);
        PlantLifeStage regrowth = stage("stage-regrowth", "REGROWTH", "재생장기", 8);
        PlantLifeStage fruiting = stage("stage-fruiting", "FRUITING", "결실기", 9);
        List<SpeciesGrowthRequirement> requirements = List.of(
                requirement(basil, seedling),
                requirement(basil, regrowth),
                requirement(tomato, seedling),
                requirement(tomato, fruiting)
        );
        when(requirementRepository.findAllActiveRequirements()).thenReturn(requirements);

        var species = plantReferenceService.getCategoryTree().categories().getFirst().species();

        assertThat(species).extracting(item -> item.name()).containsExactly("바질", "방울토마토");
        // 조회 순서를 그대로 유지해야 두 API의 자람 수준 순서가 어긋나지 않는다.
        assertThat(species.getFirst().growthStages())
                .extracting(stage -> stage.code())
                .containsExactly("SEEDLING", "REGROWTH");
        assertThat(species.get(1).growthStages())
                .extracting(stage -> stage.code())
                .containsExactly("SEEDLING", "FRUITING");
    }

    @Test
    void categoryTreeExcludesSpeciesWithoutAnyGrowthStage() {
        // 생육 기준이 없는 종은 고르면 복사할 기준이 없어 등록이 실패한다.
        // 분류까지 비면 그 분류도 빠져야 한다.
        com.potner.plant.domain.PlantCategory category =
                mock(com.potner.plant.domain.PlantCategory.class);
        when(category.isActive()).thenReturn(true);

        PlantSpecies unsupported = speciesIn(category, "species-unsupported", "기준없음");
        when(plantSpeciesRepository.findAllByActiveTrueOrderByCategorySortOrderAscNameAsc())
                .thenReturn(List.of(unsupported));
        when(requirementRepository.findAllActiveRequirements()).thenReturn(List.of());

        assertThat(plantReferenceService.getCategoryTree().categories()).isEmpty();
    }

    private SpeciesGrowthRequirement requirement(PlantSpecies species, PlantLifeStage lifeStage) {
        SpeciesGrowthRequirement requirement = mock(SpeciesGrowthRequirement.class);
        when(requirement.getSpecies()).thenReturn(species);
        when(requirement.getLifeStage()).thenReturn(lifeStage);
        return requirement;
    }

    private PlantSpecies speciesIn(
            com.potner.plant.domain.PlantCategory category,
            String id,
            String name
    ) {
        PlantSpecies species = mock(PlantSpecies.class);
        when(species.getCategory()).thenReturn(category);
        org.mockito.Mockito.lenient().when(species.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(species.getName()).thenReturn(name);
        org.mockito.Mockito.lenient().when(species.getScientificName()).thenReturn(null);
        org.mockito.Mockito.lenient().when(species.getDescription()).thenReturn(null);
        return species;
    }

    @Test
    void returnsActiveSpeciesAsResponseDtos() {
        PlantSpecies basil = species(SPECIES_ID, "바질");
        when(basil.getScientificName()).thenReturn("Ocimum basilicum L.");
        when(basil.getDescription()).thenReturn("향기로운 한해살이 허브");
        when(plantSpeciesRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(basil));

        PlantSpeciesListResponse response = plantReferenceService.getActiveSpecies();

        assertThat(response.species()).hasSize(1);
        assertThat(response.species().getFirst().speciesId()).isEqualTo(SPECIES_ID);
        assertThat(response.species().getFirst().name()).isEqualTo("바질");
        assertThat(response.species().getFirst().scientificName()).isEqualTo("Ocimum basilicum L.");
        assertThat(response.species().getFirst().description()).isEqualTo("향기로운 한해살이 허브");
    }

    @Test
    void returnsOnlyAvailableStagesProvidedByRequirementQuery() {
        PlantSpecies basil = species(SPECIES_ID, "바질");
        PlantLifeStage germination = stage(STAGE_ID, "GERMINATION", "발아기", 5);
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.of(basil));
        when(requirementRepository.findAvailableLifeStages(SPECIES_ID)).thenReturn(List.of(germination));

        GrowthStageListResponse response = plantReferenceService.getAvailableGrowthStages(SPECIES_ID);

        assertThat(response.speciesName()).isEqualTo("바질");
        assertThat(response.growthStages()).extracting("code").containsExactly("GERMINATION");
        assertThat(response.growthStages()).extracting("sortOrder").containsExactly(5);
    }

    @Test
    void rejectsMissingOrInactiveSpecies() {
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> plantReferenceService.getAvailableGrowthStages(SPECIES_ID),
                ErrorCode.PLANT_SPECIES_NOT_FOUND
        );
    }

    @Test
    void mapsDatabaseValuesAndNullsWithoutCalculation() {
        PlantSpecies basil = species(SPECIES_ID, "바질");
        PlantLifeStage germination = mock(PlantLifeStage.class);
        when(germination.getId()).thenReturn(STAGE_ID);
        when(germination.getCode()).thenReturn("GERMINATION");
        when(germination.getName()).thenReturn("발아기");
        SpeciesGrowthRequirement requirement = mock(SpeciesGrowthRequirement.class);
        when(requirement.getSpecies()).thenReturn(basil);
        when(requirement.getLifeStage()).thenReturn(germination);
        when(requirement.getTemperatureMinC()).thenReturn(new BigDecimal("21.00"));
        when(requirement.getTemperatureMaxC()).thenReturn(new BigDecimal("29.00"));
        when(requirement.getHumidityMinPct()).thenReturn(new BigDecimal("60.00"));
        when(requirement.getHumidityMaxPct()).thenReturn(new BigDecimal("80.00"));
        when(requirement.getWateringCycleDays()).thenReturn(new BigDecimal("1.00"));
        when(requirement.getRecommendedWateringMl()).thenReturn(null);
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.of(basil));
        when(requirementRepository.findActiveRequirement(SPECIES_ID, STAGE_ID))
                .thenReturn(Optional.of(requirement));

        GrowthRequirementResponse response = plantReferenceService.getGrowthRequirement(SPECIES_ID, STAGE_ID);

        assertThat(response.temperature().minC()).isEqualByComparingTo("21.00");
        assertThat(response.temperature().maxC()).isEqualByComparingTo("29.00");
        assertThat(response.humidity().minPct()).isEqualByComparingTo("60.00");
        assertThat(response.humidity().maxPct()).isEqualByComparingTo("80.00");
        assertThat(response.watering().cycleDays()).isEqualByComparingTo("1.00");
        assertThat(response.watering().recommendedVolumeMl()).isNull();
    }

    @Test
    void rejectsUnsupportedSpeciesAndStageCombination() {
        PlantSpecies basil = mock(PlantSpecies.class);
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.of(basil));
        when(requirementRepository.findActiveRequirement(SPECIES_ID, STAGE_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> plantReferenceService.getGrowthRequirement(SPECIES_ID, STAGE_ID),
                ErrorCode.GROWTH_REQUIREMENT_NOT_FOUND
        );
    }

    private PlantSpecies species(String id, String name) {
        PlantSpecies species = mock(PlantSpecies.class);
        when(species.getId()).thenReturn(id);
        when(species.getName()).thenReturn(name);
        return species;
    }

    private PlantLifeStage stage(String id, String code, String name, int sortOrder) {
        PlantLifeStage stage = mock(PlantLifeStage.class);
        when(stage.getId()).thenReturn(id);
        when(stage.getCode()).thenReturn(code);
        when(stage.getName()).thenReturn(name);
        when(stage.getSortOrder()).thenReturn(sortOrder);
        return stage;
    }

    private void assertBusinessError(Runnable invocation, ErrorCode expectedErrorCode) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedErrorCode));
    }
}
