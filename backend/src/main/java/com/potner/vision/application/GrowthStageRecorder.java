package com.potner.vision.application;

import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantLifeStageRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.vision.config.VisionProperties;
import com.potner.vision.domain.DetectedGrowthStage;
import com.potner.vision.domain.PhotoGrowthAnalysis;
import com.potner.vision.domain.PhotoGrowthAnalysisRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 판정 결과를 남기고, 단계가 진행됐으면 올린다. DB 작업만 한다.
 *
 * <p>{@link GrowthStageDetectionService} 와 나눠 둔 이유는 트랜잭션 경계다. 추론 호출은 수 초가
 * 걸리므로 트랜잭션 밖에 있어야 하는데, 한 클래스에 두고 내부에서 부르면 프록시를 타지 않아
 * {@code @Transactional} 이 조용히 무효가 된다. 그러면 단계 변경이 flush 되지 않고 판정만 남는다.
 *
 * <p>단계는 <strong>앞으로만</strong> 간다. {@code plant_life_stage.sort_order} 로 비교해 현재보다
 * 앞선 단계만 올린다. 잎이 무성한 식물의 아래쪽만 찍힌 사진이 발아기로 판정되는 일이 있는데,
 * 그대로 되돌리면 사용자가 쌓아 온 기록이 뒤로 간다.
 */
@Service
public class GrowthStageRecorder {

    private final PhotoGrowthAnalysisRepository analysisRepository;
    private final PlantRepository plantRepository;
    private final PlantLifeStageRepository lifeStageRepository;
    private final SpeciesGrowthRequirementRepository requirementRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final VisionProperties properties;
    private final Clock clock;

    public GrowthStageRecorder(
            PhotoGrowthAnalysisRepository analysisRepository,
            PlantRepository plantRepository,
            PlantLifeStageRepository lifeStageRepository,
            SpeciesGrowthRequirementRepository requirementRepository,
            PlantGrowthProfileRepository profileRepository,
            ApplicationEventPublisher eventPublisher,
            VisionProperties properties,
            Clock clock
    ) {
        this.analysisRepository = analysisRepository;
        this.plantRepository = plantRepository;
        this.lifeStageRepository = lifeStageRepository;
        this.requirementRepository = requirementRepository;
        this.profileRepository = profileRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public GrowthStageDetectionResult record(
            String photoId,
            String plantId,
            GrowthStageClassification classification
    ) {
        LocalDateTime now = now();

        if (!classification.isDetermined()) {
            save(photoId, plantId, null, null, classification, false, now);
            return GrowthStageDetectionResult.UNDETERMINED;
        }

        DetectedGrowthStage detected = classification.stage().orElseThrow();
        BigDecimal confidence = classification.confidence().orElseThrow();

        // 하한 미달도 판정 내용을 남긴다. 무엇을 얼마나 확신했는지가 남아야 하한값을 조정할
        // 근거가 된다. 남기지 않으면 하한을 낮췄을 때 무엇이 통과할지 알 수 없다.
        if (confidence.compareTo(properties.minConfidence()) < 0) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.LOW_CONFIDENCE;
        }

        Plant plant = plantRepository
                .findByIdAndStatusNot(plantId, PlantStatus.DELETED)
                .orElse(null);
        if (plant == null) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.PLANT_NOT_FOUND;
        }

        PlantLifeStage target = lifeStageRepository
                .findByCodeAndActiveTrue(detected.lifeStageCode())
                .orElse(null);
        if (target == null) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.LIFE_STAGE_NOT_FOUND;
        }

        if (target.getSortOrder() <= plant.getLifeStage().getSortOrder()) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.NOT_PROGRESSED;
        }

        // 진행 여부를 먼저 보고 플래그를 나중에 본다. 순서를 바꾸면 "올릴 만한 판정이었는데
        // 플래그 때문에 넘어갔다" 를 구분할 수 없어, 자동 승급을 켜도 되는지 판단할 근거가 사라진다.
        if (!properties.autoAdvanceEnabled()) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.AUTO_ADVANCE_DISABLED;
        }

        SpeciesGrowthRequirement requirement = requirementRepository
                .findActiveRequirement(plant.getSpecies().getId(), target.getId())
                .orElse(null);
        if (requirement == null) {
            // 모델이 아는 단계와 그 종에 정의된 단계가 다르다. 기준 없이 단계만 올리면 이후
            // 알림 판정이 이전 단계 기준을 그대로 쓴다.
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.REQUIREMENT_NOT_FOUND;
        }

        PlantGrowthProfile profile = profileRepository.findByPlantId(plantId).orElse(null);
        if (profile == null) {
            save(photoId, plantId, detected, confidence, classification, false, now);
            return GrowthStageDetectionResult.PROFILE_NOT_FOUND;
        }

        // 사용자가 손으로 바꿀 때(PlantManagementService.changeLifeStage)와 같은 두 가지를 한다.
        // 단계만 올리고 기준을 그대로 두면 성숙기 식물에 발아기 기준으로 알림이 나간다.
        plant.changeLifeStage(target, now);
        profile.applyRequirement(requirement, now);
        save(photoId, plantId, detected, confidence, classification, true, now);

        eventPublisher.publishEvent(new GrowthStageAdvancedEvent(
                plant.getUser().getId(),
                plant.getId(),
                plant.getNickname(),
                detected,
                target.getName()
        ));
        return GrowthStageDetectionResult.ADVANCED;
    }

    /**
     * 판정 결과를 남긴다.
     *
     * <p>{@code photoId} 가 PK 라 같은 사진을 다시 분석하면 덮인다. 재분석이 행을 늘리면
     * 식물별 판정 이력에 같은 날이 여러 번 나타나 오탐률 관찰이 어긋난다.
     */
    private void save(
            String photoId,
            String plantId,
            DetectedGrowthStage detected,
            BigDecimal confidence,
            GrowthStageClassification classification,
            boolean lifeStageAdvanced,
            LocalDateTime analyzedAt
    ) {
        analysisRepository.save(PhotoGrowthAnalysis.of(
                photoId,
                plantId,
                detected,
                confidence,
                classification.detectionCount(),
                classification.modelWeights(),
                BigDecimal.valueOf(classification.inferenceMs()),
                classification.requestId(),
                lifeStageAdvanced,
                analyzedAt
        ));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
