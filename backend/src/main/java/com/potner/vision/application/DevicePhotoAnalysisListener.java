package com.potner.vision.application;

import com.potner.photo.application.DevicePhotoUploadedEvent;
import com.potner.photo.application.PhotoStorage;
import com.potner.vision.config.VisionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 장치가 올린 사진을 뒤늦게 분석한다.
 *
 * <p>{@code AFTER_COMMIT} 과 {@code @Async} 를 함께 쓴다. {@code AFTER_COMMIT} 이 필요한 이유는
 * 분석이 사진 행을 참조하기 때문이다. 커밋 전에 시작하면 판정 결과를 저장할 때
 * {@code photo_growth_analysis} 의 외래 키가 아직 없는 사진을 가리킨다.
 *
 * <p>{@code @Async} 가 필요한 이유는 추론이 CPU 로 수 초 걸리기 때문이다. 동기로 두면 장치가
 * 그만큼 업로드 응답을 기다린다. 촬영은 하루 한 번이라 판정이 몇 초 늦는 것은 무해하지만,
 * 업로드가 타임아웃으로 실패하면 그날 사진 자체를 잃는다.
 *
 * <p>어떤 실패도 밖으로 내지 않는다. 사진은 이미 저장됐고, 판정은 나중에 다시 할 수 있다.
 */
@Component
public class DevicePhotoAnalysisListener {

    private static final Logger log = LoggerFactory.getLogger(DevicePhotoAnalysisListener.class);

    private final PhotoStorage photoStorage;
    private final GrowthStageDetectionService detectionService;

    public DevicePhotoAnalysisListener(
            PhotoStorage photoStorage,
            GrowthStageDetectionService detectionService
    ) {
        this.photoStorage = photoStorage;
        this.detectionService = detectionService;
    }

    @Async(VisionConfiguration.VISION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDevicePhotoUploaded(DevicePhotoUploadedEvent event) {
        try {
            analyze(event);
        } catch (RuntimeException exception) {
            log.warn(
                    "Photo growth analysis failed: photoId={}, plantId={}",
                    event.photoId(),
                    event.plantId(),
                    exception
            );
        }
    }

    private void analyze(DevicePhotoUploadedEvent event) {
        Optional<byte[]> image = photoStorage.readPlayback(event.plantId(), event.photoId());
        if (image.isEmpty()) {
            // 커밋 뒤에 읽으므로 그 사이 사진이 지워졌을 수 있다. 정상 흐름이다.
            log.warn(
                    "Photo growth analysis skipped because the image is unreadable: photoId={}, plantId={}",
                    event.photoId(),
                    event.plantId()
            );
            return;
        }

        GrowthStageDetectionResult result = detectionService.analyze(
                event.photoId(),
                event.plantId(),
                image.get()
        );

        // 결과를 항상 남긴다. 자동 승급을 켤지 판단하려면 AUTO_ADVANCE_DISABLED 가 얼마나
        // 쌓였고 그 판정이 맞았는지를 봐야 하는데, 그 근거가 이 로그와 판정 테이블뿐이다.
        log.info(
                "Photo growth analysis handled: photoId={}, plantId={}, result={}",
                event.photoId(),
                event.plantId(),
                result
        );
    }
}
