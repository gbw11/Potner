package com.potner.vision.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 추론 서비스를 쓰지 않을 때의 구현이다. 아무것도 부르지 않고 빈 값을 돌려준다.
 *
 * <p>로컬과 임시 CI 컨테이너가 이 구현으로 동작한다. 추론은 별도 컨테이너라 그 환경에는 떠 있지
 * 않지만, 사진 업로드와 분석 리스너까지는 그대로 돌기 때문에 그 앞 단계는 검증된다.
 */
public class DisabledGrowthStageClassifier implements GrowthStageClassifier {

    private static final Logger log = LoggerFactory.getLogger(DisabledGrowthStageClassifier.class);

    @Override
    public Optional<GrowthStageClassification> classify(byte[] imageBytes, String imageName) {
        log.info(
                "Growth stage inference skipped because it is disabled: imageName={}, bytes={}",
                imageName,
                imageBytes.length
        );
        return Optional.empty();
    }
}
