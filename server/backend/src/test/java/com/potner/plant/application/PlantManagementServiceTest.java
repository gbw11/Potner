package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.diary.domain.SpeciesPersonaRepository;
import com.potner.photo.application.PhotoService;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantCategory;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantLifeStageRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.PlantSpeciesRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.plant.dto.CreatePlantRequest;
import com.potner.plant.dto.PlantDetailResponse;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlantManagementServiceTest {

    private static final String USER_ID = "user-id";
    private static final String SPECIES_ID = "species-id";
    private static final String STAGE_ID = "stage-id";

    private AppUserRepository appUserRepository;
    private PlantSpeciesRepository plantSpeciesRepository;
    private PlantLifeStageRepository plantLifeStageRepository;
    private SpeciesGrowthRequirementRepository requirementRepository;
    private PlantRepository plantRepository;
    private PlantGrowthProfileRepository profileRepository;
    private SpeciesPersonaRepository personaRepository;
    private PhotoService photoService;
    private PlantManagementService service;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        plantSpeciesRepository = mock(PlantSpeciesRepository.class);
        plantLifeStageRepository = mock(PlantLifeStageRepository.class);
        requirementRepository = mock(SpeciesGrowthRequirementRepository.class);
        plantRepository = mock(PlantRepository.class);
        profileRepository = mock(PlantGrowthProfileRepository.class);
        personaRepository = mock(SpeciesPersonaRepository.class);
        photoService = mock(PhotoService.class);
        service = new PlantManagementService(
                appUserRepository,
                plantSpeciesRepository,
                plantLifeStageRepository,
                requirementRepository,
                plantRepository,
                profileRepository,
                personaRepository,
                photoService,
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void createsPlantAndProfileSnapshotForAuthenticatedUser() {
        AppUser user = mock(AppUser.class);
        PlantCategory category = category();
        PlantSpecies species = species(category);
        PlantLifeStage stage = stage();
        SpeciesGrowthRequirement requirement = requirement();
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.of(species));
        when(plantLifeStageRepository.findByIdAndActiveTrue(STAGE_ID)).thenReturn(Optional.of(stage));
        when(requirementRepository.findActiveRequirement(SPECIES_ID, STAGE_ID))
                .thenReturn(Optional.of(requirement));
        when(plantRepository.save(any(Plant.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(profileRepository.save(any(PlantGrowthProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PlantDetailResponse response = service.createPlant(
                USER_ID,
                new CreatePlantRequest(
                        SPECIES_ID,
                        STAGE_ID,
                        "  내 바질  ",
                        java.time.LocalDate.of(2026, 3, 20)));

        assertThat(response.name()).isEqualTo("내 바질");
        assertThat(response.adoptedDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 20));
        assertThat(response.category().name()).isEqualTo("허브");
        assertThat(response.growthProfile().temperature().minC()).isEqualByComparingTo("21");
        assertThat(response.growthProfile().watering().recommendedVolumeMl()).isNull();
        assertThat(response.growthProfile().customized()).isFalse();
        verify(plantRepository).save(any(Plant.class));
        verify(profileRepository).save(any(PlantGrowthProfile.class));
    }

    @Test
    void rejectsUnsupportedSpeciesAndStageBeforeSaving() {
        AppUser user = mock(AppUser.class);
        PlantCategory category = category();
        PlantSpecies species = species(category);
        PlantLifeStage stage = stage();
        when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(plantSpeciesRepository.findByIdAndActiveTrue(SPECIES_ID)).thenReturn(Optional.of(species));
        when(plantLifeStageRepository.findByIdAndActiveTrue(STAGE_ID)).thenReturn(Optional.of(stage));
        when(requirementRepository.findActiveRequirement(SPECIES_ID, STAGE_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.createPlant(
                        USER_ID,
                        new CreatePlantRequest(SPECIES_ID, STAGE_ID, "내 식물", null)),
                ErrorCode.GROWTH_REQUIREMENT_NOT_FOUND);
        verify(plantRepository, never()).save(any());
        verify(profileRepository, never()).save(any());
    }

    @Test
    void hidesMissingOrOtherUsersPlantAsNotFound() {
        when(plantRepository.findByIdAndUserIdAndStatusNot("plant-id", USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> service.getMyPlant(USER_ID, "plant-id"),
                ErrorCode.PLANT_NOT_FOUND);
        verify(profileRepository, never()).findByPlantId(any());
    }

    private PlantCategory category() {
        PlantCategory category = mock(PlantCategory.class);
        when(category.getId()).thenReturn("category-id");
        when(category.getName()).thenReturn("허브");
        when(category.isActive()).thenReturn(true);
        return category;
    }

    private PlantSpecies species(PlantCategory category) {
        PlantSpecies species = mock(PlantSpecies.class);
        when(species.getId()).thenReturn(SPECIES_ID);
        when(species.getName()).thenReturn("바질");
        when(species.getScientificName()).thenReturn("Ocimum basilicum L.");
        when(species.getDescription()).thenReturn("향기로운 허브");
        when(species.getCategory()).thenReturn(category);
        return species;
    }

    private PlantLifeStage stage() {
        PlantLifeStage stage = mock(PlantLifeStage.class);
        when(stage.getId()).thenReturn(STAGE_ID);
        when(stage.getCode()).thenReturn("GERMINATION");
        when(stage.getName()).thenReturn("발아기");
        when(stage.getSortOrder()).thenReturn(5);
        return stage;
    }

    private SpeciesGrowthRequirement requirement() {
        SpeciesGrowthRequirement requirement = mock(SpeciesGrowthRequirement.class);
        when(requirement.getId()).thenReturn("requirement-id");
        when(requirement.getRevision()).thenReturn(2);
        when(requirement.getSoilMoistureMinPct()).thenReturn(decimal("40"));
        when(requirement.getSoilMoistureMaxPct()).thenReturn(decimal("55"));
        when(requirement.getTemperatureMinC()).thenReturn(decimal("21"));
        when(requirement.getTemperatureMaxC()).thenReturn(decimal("29"));
        when(requirement.getHumidityMinPct()).thenReturn(decimal("60"));
        when(requirement.getHumidityMaxPct()).thenReturn(decimal("80"));
        when(requirement.getIlluminanceTargetLux()).thenReturn(decimal("10000"));
        when(requirement.getPhotoperiodHours()).thenReturn(decimal("15"));
        when(requirement.getDailyLightTargetLuxHour()).thenReturn(decimal("150000"));
        when(requirement.getWateringCycleDays()).thenReturn(decimal("1"));
        when(requirement.getWateringTriggerPct()).thenReturn(decimal("40"));
        return requirement;
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private void assertBusinessError(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
