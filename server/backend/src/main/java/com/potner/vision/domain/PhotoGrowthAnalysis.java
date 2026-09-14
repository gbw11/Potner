package com.potner.vision.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 사진 한 장에 대한 생장 단계 판정 결과다.
 *
 * <p>{@code photo_id} 가 그대로 PK 다. 사진 하나에 판정 하나이므로 같은 사진을 다시 분석하면
 * 행이 늘지 않고 덮인다. 사진 쪽 하루 한 장 정책과 맞물려 식물별로는 하루 한 건이 된다.
 *
 * <p>{@code detectedStage} 가 {@code null} 인 것은 정상이다. 검출이 없거나 모델이 서버가 모르는
 * 클래스만 준 경우다. 그때도 {@code detectionCount} 는 0 이 아닐 수 있어, "추론은 됐지만 판정할
 * 것이 없었다" 와 "모델과 서버의 클래스 목록이 어긋났다" 를 구분할 수 있다.
 */
@Entity
@Table(name = "photo_growth_analysis")
public class PhotoGrowthAnalysis {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "photo_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String photoId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "plant_id", length = 36, nullable = false, columnDefinition = "char(36)")
    private String plantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "detected_stage", length = 20)
    private DetectedGrowthStage detectedStage;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "detection_count", nullable = false)
    private int detectionCount;

    @Column(name = "model_weights", length = 255, nullable = false)
    private String modelWeights;

    @Column(name = "inference_ms", precision = 10, scale = 2, nullable = false)
    private BigDecimal inferenceMs;

    @Column(name = "request_id", length = 64, nullable = false)
    private String requestId;

    /**
     * 이 판정으로 실제 단계가 올라갔는지다. 자동 승급이 꺼져 있으면 항상 {@code false} 다.
     *
     * <p>판정 결과와 합치지 않는다. 합치면 "판정은 됐는데 올리지 않았다" 를 표현할 수 없고,
     * 그게 자동 승급을 켜기 전에 오탐률을 관찰하는 동안의 정상 상태다.
     */
    @Column(name = "life_stage_advanced", nullable = false)
    private boolean lifeStageAdvanced;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;

    protected PhotoGrowthAnalysis() {
    }

    public static PhotoGrowthAnalysis of(
            String photoId,
            String plantId,
            DetectedGrowthStage detectedStage,
            BigDecimal confidence,
            int detectionCount,
            String modelWeights,
            BigDecimal inferenceMs,
            String requestId,
            boolean lifeStageAdvanced,
            LocalDateTime analyzedAt
    ) {
        PhotoGrowthAnalysis analysis = new PhotoGrowthAnalysis();
        analysis.photoId = photoId;
        analysis.plantId = plantId;
        analysis.detectedStage = detectedStage;
        analysis.confidence = confidence;
        analysis.detectionCount = detectionCount;
        analysis.modelWeights = modelWeights;
        analysis.inferenceMs = inferenceMs;
        analysis.requestId = requestId;
        analysis.lifeStageAdvanced = lifeStageAdvanced;
        analysis.analyzedAt = analyzedAt;
        return analysis;
    }

    public String getPhotoId() {
        return photoId;
    }

    public String getPlantId() {
        return plantId;
    }

    public DetectedGrowthStage getDetectedStage() {
        return detectedStage;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public String getModelWeights() {
        return modelWeights;
    }

    public BigDecimal getInferenceMs() {
        return inferenceMs;
    }

    public String getRequestId() {
        return requestId;
    }

    public boolean isLifeStageAdvanced() {
        return lifeStageAdvanced;
    }

    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }
}
