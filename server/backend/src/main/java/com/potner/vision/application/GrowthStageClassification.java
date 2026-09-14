package com.potner.vision.application;

import com.potner.vision.domain.DetectedGrowthStage;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 사진 한 장에 대한 추론 결과다.
 *
 * <p>검출이 없거나 모델이 모르는 클래스만 온 경우도 정상 결과다. 그때 {@code stage} 가 비고
 * {@code detectionCount} 는 0 이 아닐 수 있다. "추론은 됐지만 판정할 것이 없었다" 와 "추론이
 * 실패했다" 를 구분해야 하므로, 실패는 이 타입이 아니라 빈 {@link Optional} 로 돌아온다.
 *
 * @param stage           가장 신뢰도가 높은 검출의 단계. 판정할 것이 없으면 비어 있다
 * @param confidence      {@code stage} 의 신뢰도. {@code stage} 가 비면 함께 비어 있다
 * @param detectionCount  모델이 준 전체 검출 수. 판정에 쓰이지 않은 것까지 센다
 * @param modelWeights    판정에 쓰인 가중치 파일. 모델을 바꾼 뒤의 결과와 구분하려면 남겨야 한다
 * @param inferenceMs     추론 소요 시간. CPU 추론이라 느려지는 것을 관찰할 근거가 된다
 * @param requestId       추론 서비스가 부여한 식별자. 그쪽 로그와 대조할 유일한 열쇠다
 */
public record GrowthStageClassification(
        Optional<DetectedGrowthStage> stage,
        Optional<BigDecimal> confidence,
        int detectionCount,
        String modelWeights,
        double inferenceMs,
        String requestId
) {

    public GrowthStageClassification {
        stage = stage == null ? Optional.empty() : stage;
        confidence = confidence == null ? Optional.empty() : confidence;
    }

    /** 판정할 것이 없었던 결과다. 추론 자체는 성공했다. */
    public static GrowthStageClassification undetermined(
            int detectionCount,
            String modelWeights,
            double inferenceMs,
            String requestId
    ) {
        return new GrowthStageClassification(
                Optional.empty(),
                Optional.empty(),
                detectionCount,
                modelWeights,
                inferenceMs,
                requestId
        );
    }

    public boolean isDetermined() {
        return stage.isPresent();
    }
}
