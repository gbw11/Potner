package com.potner.plant.application;

import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.photo.application.PhotoService;
import com.potner.photo.dto.PhotoResponse;
import com.potner.plant.domain.GrowthProfileValues;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantLifeStageRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.diary.domain.SpeciesPersona;
import com.potner.diary.domain.SpeciesPersonaRepository;
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
    private final SpeciesPersonaRepository personaRepository;
    private final PhotoService photoService;
    private final Clock clock;

    public PlantManagementService(
            AppUserRepository appUserRepository,
            PlantSpeciesRepository plantSpeciesRepository,
            PlantLifeStageRepository plantLifeStageRepository,
            SpeciesGrowthRequirementRepository requirementRepository,
            PlantRepository plantRepository,
            PlantGrowthProfileRepository profileRepository,
            SpeciesPersonaRepository personaRepository,
            PhotoService photoService,
            Clock clock
    ) {
        this.appUserRepository = appUserRepository;
        this.plantSpeciesRepository = plantSpeciesRepository;
        this.plantLifeStageRepository = plantLifeStageRepository;
        this.requirementRepository = requirementRepository;
        this.plantRepository = plantRepository;
        this.profileRepository = profileRepository;
        this.personaRepository = personaRepository;
        this.photoService = photoService;
        this.clock = clock;
    }

    /**
     * 대표 사진을 응답에 채운다. 지정하지 않았으면 null 이다.
     *
     * <p>URL 조립은 사진 쪽 책임이라 식별자만 넘기고 결과를 받는다.
     */
    private PhotoResponse representativePhotoOf(Plant plant) {
        String photoId = plant.getRepresentativePhotoId();
        if (photoId == null) {
            return null;
        }
        return photoService.findResponsesByIds(java.util.List.of(photoId)).get(photoId);
    }

    /**
     * 종에 정해진 성격을 찾는다. 없으면 null 이다.
     *
     * <p>없다고 예외를 내지 않는다. 종이 먼저 늘고 페르소나가 나중에 오는 구간이 있는데,
     * 그때 성격이 없다는 이유로 프로필 조회 전체가 실패하면 안 된다. 일기 생성이 같은
     * 상황에서 기본 화법으로 넘어가는 것과 같은 판단이다.
     */
    private SpeciesPersona personaOf(Plant plant) {
        return personaRepository.findById(plant.getSpecies().getId()).orElse(null);
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

        Plant plant = plantRepository.save(Plant.create(
                user,
                species,
                lifeStage,
                request.name(),
                request.adoptedDate(),
                now));
        PlantGrowthProfile profile = profileRepository.save(
                PlantGrowthProfile.create(plant, requirement, now));
        return PlantDetailResponse.from(plant, profile, representativePhotoOf(plant), personaOf(plant));
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

        // 식물마다 사진을 조회하면 목록 길이만큼 쿼리가 나간다. 한 번에 받아 매핑한다.
        Map<String, PhotoResponse> representativePhotos = photoService.findResponsesByIds(
                plants.stream().map(Plant::getRepresentativePhotoId).toList());

        var responses = plants.stream()
                .map(plant -> {
                    PlantGrowthProfile profile = profiles.get(plant.getId());
                    if (profile == null) {
                        throw new BusinessException(ErrorCode.PLANT_GROWTH_PROFILE_NOT_FOUND);
                    }
                    // Map.of() 는 null 키 조회에서 NPE 를 내므로 먼저 거른다.
                    String photoId = plant.getRepresentativePhotoId();
                    return PlantSummaryResponse.from(
                            plant,
                            profile.isCustomized(),
                            photoId == null ? null : representativePhotos.get(photoId)
                    );
                })
                .toList();
        return new PlantListResponse(responses);
    }

    public PlantDetailResponse getMyPlant(String userId, String plantId) {
        Plant plant = findOwnedPlant(userId, plantId);
        return PlantDetailResponse.from(plant, findProfile(plantId), representativePhotoOf(plant), personaOf(plant));
    }

    @Transactional
    public PlantDetailResponse updatePlant(String userId, String plantId, UpdatePlantRequest request) {
        Plant plant = findOwnedPlant(userId, plantId);
        if (request.name() != null) {
            validatePlantName(request.name());
            plant.updateNickname(request.name(), now());
        }
        if (request.adoptedDate() != null) {
            plant.updateAdoptedDate(request.adoptedDate(), now());
        }
        return PlantDetailResponse.from(plant, findProfile(plantId), representativePhotoOf(plant), personaOf(plant));
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
        return PlantDetailResponse.from(plant, profile, representativePhotoOf(plant), personaOf(plant));
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
