package com.potner.vision.application;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 사진 한 장을 판정해 결과를 남긴다.
 *
 * <p>추론 호출과 DB 작업의 경계를 나누는 것이 이 클래스의 역할이다. 추론은 CPU 로 수 초가 걸리고
 * {@code YOLO_MAX_CONCURRENCY=1} 이라 대기가 겹치는데, 트랜잭션 안에서 부르면 그동안 DB 커넥션을
 * 붙잡아 커넥션 풀이 먼저 마른다. 그래서 여기에는 트랜잭션이 없고, 저장은
 * {@link GrowthStageRecorder} 가 자기 트랜잭션에서 처리한다.
 */
@Service
public class GrowthStageDetectionService {

    private final GrowthStageClassifier classifier;
    private final GrowthStageRecorder recorder;

    public GrowthStageDetectionService(
            GrowthStageClassifier classifier,
            GrowthStageRecorder recorder
    ) {
        this.classifier = classifier;
        this.recorder = recorder;
    }

    public GrowthStageDetectionResult analyze(String photoId, String plantId, byte[] imageBytes) {
        Optional<GrowthStageClassification> classified = classifier.classify(imageBytes, photoId);
        if (classified.isEmpty()) {
            // 비활성이거나 호출이 실패했다. 판정 내용이 없으므로 남길 것도 없다. 실패 사유는
            // 분류기가 이미 로그로 남겼다.
            return GrowthStageDetectionResult.INFERENCE_FAILED;
        }
        return recorder.record(photoId, plantId, classified.get());
    }
}
