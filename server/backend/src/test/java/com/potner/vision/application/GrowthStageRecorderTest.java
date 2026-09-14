package com.potner.vision.application;

import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantGrowthProfile;
import com.potner.plant.domain.PlantGrowthProfileRepository;
import com.potner.plant.domain.PlantLifeStage;
import com.potner.plant.domain.PlantLifeStageRepository;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantSpecies;
import com.potner.plant.domain.PlantStatus;
import com.potner.plant.domain.SpeciesGrowthRequirement;
import com.potner.plant.domain.SpeciesGrowthRequirementRepository;
import com.potner.user.domain.AppUser;
import com.potner.vision.config.VisionProperties;
import com.potner.vision.domain.DetectedGrowthStage;
import com.potner.vision.domain.PhotoGrowthAnalysis;
import com.potner.vision.domain.PhotoGrowthAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrowthStageRecorderTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final String PHOTO_ID = "11111111-1111-1111-1111-111111111111";
    private static final String PLANT_ID = "22222222-2222-2222-2222-222222222222";
    private static final String USER_ID = "33333333-3333-3333-3333-333333333333";
    private static final String SPECIES_ID = "44444444-4444-4444-4444-444444444444";
    private static final String TARGET_STAGE_ID = "55555555-5555-5555-5555-555555555555";

    /** 참조 데이터의 sort_order 다. 영양생장기(40)가 발아기(5)보다 앞선다. */
    private static final int GERMINATION_ORDER = 5;
    private static final int VEGETATIVE_ORDER = 40;
    private static final int FLOWERING_ORDER = 70;

    @Mock
    private PhotoGrowthAnalysisRepository analysisRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private PlantLifeStageRepository lifeStageRepository;

    @Mock
    private SpeciesGrowthRequirementRepository requirementRepository;

    @Mock
    private PlantGrowthProfileRepository profileRepository;

    @Mock
    private com.potner.bloom.application.BloomService bloomService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GrowthStageRecorder recorder(boolean autoAdvanceEnabled) {
        return recorder(autoAdvanceEnabled, true);
    }

    private GrowthStageRecorder recorder(boolean autoAdvanceEnabled, boolean autoBloomEnabled) {
        return recorder(autoAdvanceEnabled, autoBloomEnabled, true, 30);
    }

    private GrowthStageRecorder recorder(
            boolean autoAdvanceEnabled,
            boolean autoBloomEnabled,
            boolean autoSproutEnabled,
            int sproutCooldownDays
    ) {
        return new GrowthStageRecorder(
                analysisRepository,
                plantRepository,
                lifeStageRepository,
                requirementRepository,
                profileRepository,
                bloomService,
                eventPublisher,
                new VisionProperties(
                        true,
                        "http://yolo:8000",
                        5,
                        35,
                        new BigDecimal("0.25"),
                        640,
                        new BigDecimal("0.60"),
                        autoAdvanceEnabled,
                        autoBloomEnabled,
                        7,
                        autoSproutEnabled,
                        sproutCooldownDays
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void storesTheResultWithoutAStageWhenNothingWasDetected() {
        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, undetermined());

        assertThat(result).isEqualTo(GrowthStageDetectionResult.UNDETERMINED);
        PhotoGrowthAnalysis saved = captureSaved();
        assertThat(saved.getDetectedStage()).isNull();
        assertThat(saved.getConfidence()).isNull();
        assertThat(saved.isLifeStageAdvanced()).isFalse();
    }

    @Test
    void storesTheStageEvenWhenConfidenceIsBelowTheThreshold() {
        // 하한을 조정할 근거가 남아야 한다. 남기지 않으면 하한을 낮췄을 때 무엇이 통과할지 모른다.
        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.42"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.LOW_CONFIDENCE);
        PhotoGrowthAnalysis saved = captureSaved();
        assertThat(saved.getDetectedStage()).isEqualTo(DetectedGrowthStage.VEGETATIVE);
        assertThat(saved.getConfidence()).isEqualByComparingTo("0.42");
        assertThat(saved.isLifeStageAdvanced()).isFalse();
        verify(plantRepository, never()).findByIdAndStatusNot(any(), any());
    }

    @Test
    void doesNotMoveTheStageBackwards() {
        // 잎이 무성한 식물의 아래쪽만 찍힌 사진이 발아기로 판정되는 일이 있다. 그대로 되돌리면
        // 사용자가 쌓아 온 기록이 뒤로 간다.
        Plant plant = givenPlantAtStage(VEGETATIVE_ORDER);
        givenTargetStage(DetectedGrowthStage.GERMINATION, GERMINATION_ORDER);

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.GERMINATION, "0.95"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.NOT_PROGRESSED);
        verify(plant, never()).changeLifeStage(any(), any());
        assertThat(captureSaved().isLifeStageAdvanced()).isFalse();
    }

    @Test
    void notifiesOnlyOnceWhileTheStageStaysTheSame() {
        // 알림은 단계가 바뀔 때 한 번씩만 나간다. 같은 단계가 매일 판정되어도 두 번째부터는
        // 이벤트가 없다. 이 동작을 이력 기준으로 바꾸면 손으로 되돌린 사용자가 단계가 다시
        // 올라간 것을 영영 알 수 없게 되므로, 현재 단계로만 막는다는 결정을 여기서 잠근다.
        Plant plant = givenPlantAtStage(VEGETATIVE_ORDER);
        givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.93"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.NOT_PROGRESSED);
        verify(plant, never()).changeLifeStage(any(), any());
        verify(eventPublisher, never()).publishEvent(any(GrowthStageAdvancedEvent.class));
        // 알림은 안 가지만 판정 이력은 남는다. 모델이 매일 무엇을 보고 있었는지가 필요하다.
        assertThat(captureSaved().getDetectedStage()).isEqualTo(DetectedGrowthStage.VEGETATIVE);
    }

    @Test
    void recordsThatItWouldHaveAdvancedWhenAutoAdvanceIsOff() {
        // 기본 상태다. 이 결과가 쌓인 것을 보고 판정이 맞는지 확인한 뒤 플래그를 켠다.
        Plant plant = givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);

        GrowthStageDetectionResult result = recorder(false)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.91"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.AUTO_ADVANCE_DISABLED);
        verify(plant, never()).changeLifeStage(any(), any());
        verify(eventPublisher, never()).publishEvent(any(GrowthStageAdvancedEvent.class));
        PhotoGrowthAnalysis saved = captureSaved();
        assertThat(saved.getDetectedStage()).isEqualTo(DetectedGrowthStage.VEGETATIVE);
        assertThat(saved.isLifeStageAdvanced()).isFalse();
    }

    @Test
    void advancesTheStageAndAppliesTheNewRequirement() {
        Plant plant = givenPlantAtStage(GERMINATION_ORDER);
        PlantLifeStage target = givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);
        SpeciesGrowthRequirement requirement = givenRequirement();
        PlantGrowthProfile profile = givenProfile();

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.91"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.ADVANCED);
        LocalDateTime expectedNow = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        verify(plant).changeLifeStage(target, expectedNow);
        // 단계만 올리고 기준을 두면 성숙기 식물에 발아기 기준으로 알림이 나간다.
        verify(profile).applyRequirement(requirement, expectedNow);
        assertThat(captureSaved().isLifeStageAdvanced()).isTrue();

        ArgumentCaptor<GrowthStageAdvancedEvent> event =
                ArgumentCaptor.forClass(GrowthStageAdvancedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        assertThat(event.getValue().plantId()).isEqualTo(PLANT_ID);
        assertThat(event.getValue().detectedStage()).isEqualTo(DetectedGrowthStage.VEGETATIVE);
        assertThat(event.getValue().lifeStageName()).isEqualTo("영양생장기");
    }

    @Test
    void doesNotAdvanceWhenTheSpeciesHasNoRequirementForThatStage() {
        // 기준 없이 단계만 올리면 이후 알림 판정이 이전 단계 기준을 그대로 쓴다.
        Plant plant = givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);
        when(requirementRepository.findActiveRequirement(SPECIES_ID, TARGET_STAGE_ID))
                .thenReturn(Optional.empty());

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.91"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.REQUIREMENT_NOT_FOUND);
        verify(plant, never()).changeLifeStage(any(), any());
        verify(eventPublisher, never()).publishEvent(any(GrowthStageAdvancedEvent.class));
    }

    @Test
    void recordsABloomEvenWhenTheStageDoesNotAdvance() {
        // 사용자가 이미 단계를 개화기로 올려 두었다. 승급은 NOT_PROGRESSED 지만 새로 핀 꽃은
        // 기록되어야 한다 — 개화 기록과 단계 승급은 별개라는 결정을 여기서 잠근다.
        givenPlantAtStage(FLOWERING_ORDER);
        givenTargetStage(DetectedGrowthStage.FLOWERING, FLOWERING_ORDER);
        when(analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        eq(PLANT_ID), any()))
                .thenReturn(Optional.empty());

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.FLOWERING, "0.88"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.NOT_PROGRESSED);
        verify(bloomService).recordFromDevice(USER_ID, PLANT_ID, "로지", 7);
    }

    @Test
    void doesNotRecordABloomWhileFloweringContinues() {
        // 개화는 몇 주씩 이어진다. 직전 확신 판정이 이미 개화면 계속 피어 있는 것이지 새 개화가
        // 아니다. 매일 기록이 쌓이면 안 된다.
        givenPlantAtStage(FLOWERING_ORDER);
        givenTargetStage(DetectedGrowthStage.FLOWERING, FLOWERING_ORDER);
        PhotoGrowthAnalysis previous = org.mockito.Mockito.mock(PhotoGrowthAnalysis.class);
        when(previous.getDetectedStage()).thenReturn(DetectedGrowthStage.FLOWERING);
        when(analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        eq(PLANT_ID), any()))
                .thenReturn(Optional.of(previous));

        recorder(true).record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.FLOWERING, "0.88"));

        verify(bloomService, never()).recordFromDevice(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void doesNotRecordABloomForNonFloweringDetections() {
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);

        recorder(false).record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.91"));

        verify(bloomService, never()).recordFromDevice(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void doesNotRecordABloomWhenAutoBloomIsOff() {
        givenPlantAtStage(FLOWERING_ORDER);
        givenTargetStage(DetectedGrowthStage.FLOWERING, FLOWERING_ORDER);

        recorder(true, false)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.FLOWERING, "0.88"));

        verify(bloomService, never()).recordFromDevice(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void notifiesSproutOnTheFirstGerminationDetection() {
        // 발아기는 생장 단계의 맨 아래라 승급으로는 알릴 수 없다(NOT_PROGRESSED). 씨앗을 심고
        // 기다리던 사용자에게는 개화만큼 큰 소식이므로 별도 경로로 보낸다.
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.GERMINATION, GERMINATION_ORDER);

        GrowthStageDetectionResult result = recorder(true)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.GERMINATION, "0.73"));

        assertThat(result).isEqualTo(GrowthStageDetectionResult.NOT_PROGRESSED);
        verify(eventPublisher).publishEvent(any(PlantSproutedEvent.class));
    }

    @Test
    void doesNotNotifySproutAgainWithinTheCooldown() {
        // 사진은 매일 올라오고 발아 상태는 며칠 이어진다. 걸러 두지 않으면 같은 알림이 매일 간다.
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.GERMINATION, GERMINATION_ORDER);
        PhotoGrowthAnalysis previous = org.mockito.Mockito.mock(PhotoGrowthAnalysis.class);
        when(previous.getAnalyzedAt())
                .thenReturn(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3));
        when(analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        eq(PLANT_ID), any()))
                .thenReturn(Optional.of(previous));

        recorder(true).record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.GERMINATION, "0.73"));

        verify(eventPublisher, never()).publishEvent(any(PlantSproutedEvent.class));
    }

    @Test
    void notifiesSproutAgainWhenTheCooldownIsZero() {
        // 시연에서 반복해 보여줄 때 쓴다. 판정될 때마다 알린다.
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.GERMINATION, GERMINATION_ORDER);
        PhotoGrowthAnalysis previous = org.mockito.Mockito.mock(PhotoGrowthAnalysis.class);
        when(previous.getAnalyzedAt())
                .thenReturn(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3));
        when(analysisRepository
                .findFirstByPlantIdAndDetectedStageIsNotNullAndConfidenceGreaterThanEqualOrderByAnalyzedAtDesc(
                        eq(PLANT_ID), any()))
                .thenReturn(Optional.of(previous));

        recorder(true, true, true, 0)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.GERMINATION, "0.73"));

        verify(eventPublisher).publishEvent(any(PlantSproutedEvent.class));
    }

    @Test
    void doesNotNotifySproutForOtherStages() {
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.VEGETATIVE, VEGETATIVE_ORDER);

        recorder(false).record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.VEGETATIVE, "0.91"));

        verify(eventPublisher, never()).publishEvent(any(PlantSproutedEvent.class));
    }

    @Test
    void doesNotNotifySproutWhenTheFlagIsOff() {
        givenPlantAtStage(GERMINATION_ORDER);
        givenTargetStage(DetectedGrowthStage.GERMINATION, GERMINATION_ORDER);

        recorder(true, true, false, 30)
                .record(PHOTO_ID, PLANT_ID, determined(DetectedGrowthStage.GERMINATION, "0.73"));

        verify(eventPublisher, never()).publishEvent(any(PlantSproutedEvent.class));
    }

    private GrowthStageClassification undetermined() {
        return GrowthStageClassification.undetermined(0, "/app/models/best.pt", 700.0, "req-1");
    }

    private GrowthStageClassification determined(DetectedGrowthStage stage, String confidence) {
        return new GrowthStageClassification(
                Optional.of(stage),
                Optional.of(new BigDecimal(confidence)),
                1,
                "/app/models/best.pt",
                812.5,
                "req-1"
        );
    }

    private Plant givenPlantAtStage(int sortOrder) {
        PlantLifeStage current = org.mockito.Mockito.mock(PlantLifeStage.class);
        when(current.getSortOrder()).thenReturn(sortOrder);

        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        PlantSpecies species = org.mockito.Mockito.mock(PlantSpecies.class);
        Plant plant = org.mockito.Mockito.mock(Plant.class);
        when(plant.getLifeStage()).thenReturn(current);
        org.mockito.Mockito.lenient().when(plant.getId()).thenReturn(PLANT_ID);
        org.mockito.Mockito.lenient().when(plant.getNickname()).thenReturn("로지");
        org.mockito.Mockito.lenient().when(plant.getUser()).thenReturn(user);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(USER_ID);
        org.mockito.Mockito.lenient().when(plant.getSpecies()).thenReturn(species);
        org.mockito.Mockito.lenient().when(species.getId()).thenReturn(SPECIES_ID);

        when(plantRepository.findByIdAndStatusNot(PLANT_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
        return plant;
    }

    private PlantLifeStage givenTargetStage(DetectedGrowthStage detected, int sortOrder) {
        PlantLifeStage target = org.mockito.Mockito.mock(PlantLifeStage.class);
        when(target.getSortOrder()).thenReturn(sortOrder);
        org.mockito.Mockito.lenient().when(target.getId()).thenReturn(TARGET_STAGE_ID);
        org.mockito.Mockito.lenient().when(target.getName()).thenReturn("영양생장기");
        when(lifeStageRepository.findByCodeAndActiveTrue(detected.lifeStageCode()))
                .thenReturn(Optional.of(target));
        return target;
    }

    private SpeciesGrowthRequirement givenRequirement() {
        SpeciesGrowthRequirement requirement = org.mockito.Mockito.mock(SpeciesGrowthRequirement.class);
        when(requirementRepository.findActiveRequirement(SPECIES_ID, TARGET_STAGE_ID))
                .thenReturn(Optional.of(requirement));
        return requirement;
    }

    private PlantGrowthProfile givenProfile() {
        PlantGrowthProfile profile = org.mockito.Mockito.mock(PlantGrowthProfile.class);
        when(profileRepository.findByPlantId(PLANT_ID)).thenReturn(Optional.of(profile));
        return profile;
    }

    private PhotoGrowthAnalysis captureSaved() {
        ArgumentCaptor<PhotoGrowthAnalysis> captor =
                ArgumentCaptor.forClass(PhotoGrowthAnalysis.class);
        verify(analysisRepository).save(captor.capture());
        assertThat(captor.getValue().getPhotoId()).isEqualTo(PHOTO_ID);
        assertThat(captor.getValue().getPlantId()).isEqualTo(PLANT_ID);
        return captor.getValue();
    }
}
