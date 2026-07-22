package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.Plant;
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
import com.potner.plant.dto.ChangeLifeStageRequest;
import com.potner.plant.dto.CreatePlantRequest;
import com.potner.plant.dto.GrowthProfileResponse;
import com.potner.plant.dto.PlantDetailResponse;
import com.potner.plant.dto.PlantListResponse;
import com.potner.plant.dto.PlantSummaryResponse;
import com.potner.plant.dto.UpdateGrowthProfileRequest;
import com.potner.plant.dto.UpdatePlantRequest;
import com.potner.user.domain.AppUser;
import com.potner.user.domain.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PlantManagementService {

    private final AppUserRepository appUserRepository;
    private final PlantSpeciesRepository plantSpeciesRepository;
    private final PlantLifeStageRepository plantLifeStageRepository;
    private final SpeciesGrowthRequirementRepository requirementRepository;
    private final PlantRepository plantRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final Clock clock;

    public PlantManagementService(
            AppUserRepository appUserRepository,
            PlantSpeciesRepository plantSpeciesRepository,
            PlantLifeStageRepository plantLifeStageRepository,
            SpeciesGrowthRequirementRepository requirementRepository,
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.plantSpeciesRepository = plantSpeciesRepository;
        this.plantLifeStageRepository = plantLifeStageRepository;
        this.requirementRepository = requirementRepository;
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.clock = clock;
    }

    @Transactional
    public PlantDetailResponse createPlant(String userId, CreatePlantRequest request) {
        validatePlantName(request.name());
        AppUser user = findUser(userId);
        PlantSpecies species = findActiveSpecies(request.speciesId());
        if (!species.getCategory().isActive()) {
            throw new BusinessException(ErrorCode.PLANT_SPECIES_NOT_FOUND);
        }
        PlantLifeStage lifeStage = findActiveLifeStage(request.lifeStageId());
        SpeciesGrowthRequirement requirement = findRequirement(species.getId(), lifeStage.getId());
        LocalDateTime now = now();

        Plant plant = plantRepository.save(Plant.create(user, species, lifeStage, request.name(), now));
        PlantGrowthProfile profile = profileRepository.save(
                PlantGrowthProfile.create(plant, requirement, now));
        return PlantDetailResponse.from(plant, profile);
    }

    public PlantListResponse getMyPlants(String userId) {
        findUser(userId);
        var plants = plantRepository.findAllOwnedNotDeleted(
                userId,
                PlantStatus.DELETED);
        if (plants.isEmpty()) {
            return new PlantListResponse(java.util.List.of());
        }

        Map<String, PlantGrowthProfile> profiles = profileRepository.findAllByPlantIdIn(
                        plants.stream().map(Plant::getId).toList())
                .stream()
                .collect(Collectors.toMap(PlantGrowthProfile::getPlantId, Function.identity()));

        var responses = plants.stream()
                .map(plant -> {
                    PlantGrowthProfile profile = profiles.get(plant.getId());
                    if (profile == null) {
                        throw new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND);
                    }
                    return PlantSummaryResponse.from(plant, profile.isCustomized());
                })
                .toList();
        return new PlantListResponse(responses);
    }

    public PlantDetailResponse getMyPlant(String userId, String plantId) {
        Plant plant = findOwnedPlant(userId, plantId);
        return PlantDetailResponse.from(plant, findProfile(plantId));
    }

    @Transactional
    public PlantDetailResponse updatePlant(String userId, String plantId, UpdatePlantRequest request) {
        Plant plant = findOwnedPlant(userId, plantId);
        if (request.name() != null) {
            validatePlantName(request.name());
            plant.updateNickname(request.name(), now());
        }
        return PlantDetailResponse.from(plant, findProfile(plantId));
    }

    @Transactional
    public void deletePlant(String userId, String plantId) {
        Plant plant = findOwnedPlant(userId, plantId);
        plant.delete(now());
    }

    public GrowthProfileResponse getGrowthProfile(String userId, String plantId) {
        Plant plant = findOwnedPlant(userId, plantId);
        return GrowthProfileResponse.from(findProfile(plantId), plant.getLifeStage());
    }

    @Transactional
    public GrowthProfileResponse updateGrowthProfile(
            String userId,
            String plantId,
            UpdateGrowthProfileRequest request
    ) {
        Plant plant = findOwnedPlant(userId, plantId);
        if (request.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_GROWTH_PROFILE);
        }
        PlantGrowthProfile profile = findProfile(plantId);
        GrowthProfileValues updatedValues = request.merge(GrowthProfileValues.from(profile));
        GrowthProfileValidator.validate(updatedValues);
        profile.customize(updatedValues, now());
        return GrowthProfileResponse.from(profile, plant.getLifeStage());
    }

    @Transactional
    public GrowthProfileResponse resetGrowthProfile(String userId, String plantId) {
        Plant plant = findOwnedPlant(userId, plantId);
        SpeciesGrowthRequirement requirement = findRequirement(
                plant.getSpecies().getId(),
                plant.getLifeStage().getId());
        PlantGrowthProfile profile = findProfile(plantId);
        profile.applyRequirement(requirement, now());
        return GrowthProfileResponse.from(profile, plant.getLifeStage());
    }

    @Transactional
    public PlantDetailResponse changeLifeStage(
            String userId,
            String plantId,
            ChangeLifeStageRequest request
    ) {
        Plant plant = findOwnedPlant(userId, plantId);
        PlantLifeStage newLifeStage = findActiveLifeStage(request.lifeStageId());
        SpeciesGrowthRequirement requirement = findRequirement(
                plant.getSpecies().getId(),
                newLifeStage.getId());
        PlantGrowthProfile profile = findProfile(plantId);
        LocalDateTime now = now();

        plant.changeLifeStage(newLifeStage, now);
        profile.applyRequirement(requirement, now);
        return PlantDetailResponse.from(plant, profile);
    }

    private AppUser findUser(String userId) {
        return appUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private PlantSpecies findActiveSpecies(String speciesId) {
        return plantSpeciesRepository.findByIdAndActiveTrue(speciesId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_SPECIES_NOT_FOUND));
    }

    private PlantLifeStage findActiveLifeStage(String lifeStageId) {
        return plantLifeStageRepository.findByIdAndActiveTrue(lifeStageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_LIFE_STAGE_NOT_FOUND));
    }

    private SpeciesGrowthRequirement findRequirement(String speciesId, String lifeStageId) {
        return requirementRepository.findActiveRequirement(speciesId, lifeStageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GROWTH_REQUIREMENT_NOT_FOUND));
    }

    private Plant findOwnedPlant(String userId, String plantId) {
        return plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }

    private PlantGrowthProfile findProfile(String plantId) {
        return profileRepository.findByPlantId(plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND));
    }

    private void validatePlantName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 50) {
            throw new BusinessException(ErrorCode.INVALID_PLANT_NAME);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
