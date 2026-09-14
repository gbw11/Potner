package com.potner.vision.application;

import com.potner.bloom.application.BloomService;
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
 *
 * <p>그래서 알림은 <strong>단계가 바뀔 때 한 번씩만</strong> 나간다. 같은 단계가 매일 판정되어도
 * 두 번째부터는 {@code NOT_PROGRESSED} 라 이벤트가 발행되지 않는다. 모델이 아는 단계가 3종이므로
 * 식물 한 마리가 받는 알림은 일생 동안 최대 세 번이다.
 *
 * <p>중복 판정은 <strong>현재 단계로만</strong> 막고 승급 이력을 보지 않는다. 의도된 선택이다.
 * 사용자가 앱에서 단계를 손으로 되돌리면({@code PATCH /plants/{plantId}/growth-stage} 에 순서
 * 검증이 없다) 다음 사진에서 같은 알림이 다시 갈 수 있다. 그건 사용자가 되돌린 결과이므로 다시
 * 알리는 편이 맞다고 보았다. 일생 한 번을 엄격히 보장하려면 {@code photo_growth_analysis} 에서
 * {@code life_stage_advanced = 1} 인 이력을 함께 봐야 하는데, 그러면 실수로 되돌린 사용자가 단계가
 * 다시 올라간 것을 영영 알 수 없다.
 */
@Service
public class GrowthStageRecorder {

    private final PhotoGrowthAnalysisRepository analysisRepository;
    private final PlantRepository plantRepository;
    private final PlantLifeStageRepository lifeStageRepository;
    private final SpeciesGrowthRequirementRepository requirementRepository;
    private final PlantGrowthProfileRepository profileRepository;
    private final BloomService bloomService;
    private final ApplicationEventPublisher eventPublisher;
    private final VisionProperties properties;
    private final Clock clock;

    public GrowthStageRecorder(
            PhotoGrowthAnalysisRepository analysisRepository,
            PlantRepository plantRepository,
            PlantLifeStageRepository lifeStageRepository,
            SpeciesGrowthRequirementRepository requirementRepository,
            PlantGrowthProfileRepository profileRepository,
            BloomService bloomService,
            ApplicationEventPublisher eventPublisher,
            VisionProperties properties,
            Clock clock
    ) {
        this.analysisRepository = analysisRepository;
        this.plantRepository = plantRepository;
        this.lifeStageRepository = lifeStageRepository;
        this.requirementRepository = requirementRepository;
        this.profileRepository = profileRepository;
        this.bloomService = bloomService;
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

        // 개화 기록은 단계 승급과 별개다. 사용자가 이미 단계를 개화기로 올려 두었어도
        // (아래에서 NOT_PROGRESSED 가 되어도) 새로 핀 꽃은 기록되어야 한다.
        maybeRecordBloom(plant, detected);
        maybeNotifySprout(plant, detected, now);

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
     * 개화 판정이면 개화 기록을 남긴다.
     *
     * <p>"새로 피었는지" 는 직전 <strong>확신 판정</strong>과 비교해 정한다. 개화는 몇 주씩
     * 이어지므로 매일 찍히는 개화 판정을 그대로 남기면 기록이 날마다 쌓인다. 직전 확신 판정이
     * 이미 개화면 계속 피어 있는 것이다. 그 비교로 못 거르는 판정 왕복(개화 ↔ 영양생장)은
     * {@code BloomService#recordFromDevice} 의 냉각 기간이 거른다.
     *
     * <p>현재 사진의 판정을 저장하기 <strong>전에</strong> 불러야 한다. 저장 후에는 자기 자신이
     * 직전 판정으로 잡혀 어떤 개화도 새 개화가 아니게 된다.
     *
     * <p>자동 승급이 함께 켜져 있고 이 사진으로 단계도 올라가면 승급 푸시와 개화 푸시가 같이
     * 나갈 수 있다. 서로 다른 사실(단계가 바뀌었다 / 꽃이 폈다)이라 합치지 않는다.
     */
    private void maybeRecordBloom(Plant plant, DetectedGrowthStage detected) {
        if (detected != DetectedGrowthStage.FLOWERING || !properties.autoBloomEnabled()) {
            return;
        }
        boolean alreadyFlowering = analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        plant.getId(), properties.minConfidence())
                .map(previous -> previous.getDetectedStage() == DetectedGrowthStage.FLOWERING)
                .orElse(false);
        if (alreadyFlowering) {
            return;
        }
        bloomService.recordFromDevice(
                plant.getUser().getId(),
                plant.getId(),
                plant.getNickname(),
                properties.bloomCooldownDays()
        );
    }

    /**
     * 싹이 텄다는 것을 사용자에게 알린다.
     *
     * <p>단계 승급과 별개다. 발아기는 생장 단계의 맨 아래라 올라갈 곳이 없어, 판정이 정확해도
     * 승급 경로로는 {@code NOT_PROGRESSED} 로 조용히 끝난다. 개화 기록을 승급과 나눠 둔 것과
     * 같은 이유로 여기서 따로 발행한다.
     *
     * <p>냉각 기간 안에 확신 판정이 하나라도 있으면 건너뛴다. 사진은 하루 한 장씩 계속 올라오고
     * 발아 상태는 며칠 이어지므로, 걸러 두지 않으면 매일 같은 알림이 간다.
     */
    private void maybeNotifySprout(Plant plant, DetectedGrowthStage detected, LocalDateTime now) {
        if (detected != DetectedGrowthStage.GERMINATION || !properties.autoSproutEnabled()) {
            return;
        }
        // 이 사진의 판정을 저장하기 전에 봐야 한다. 저장 후면 자기 자신이 직전 판정으로 잡힌다.
        boolean recentlyDetermined = analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        plant.getId(), properties.minConfidence())
                .map(previous -> !previous.getAnalyzedAt()
                        .isBefore(now.minusDays(properties.sproutCooldownDays())))
                .orElse(false);
        if (recentlyDetermined) {
            return;
        }
        eventPublisher.publishEvent(new PlantSproutedEvent(
                plant.getUser().getId(),
                plant.getId(),
                plant.getNickname()
        ));
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
