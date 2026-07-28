package com.potner.diary.application;

import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 하루가 끝난 뒤 활성 식물마다 전일 일기를 한 편씩 남긴다.
 *
 * <p>광량 집계보다 뒤에 돈다. 일기가 전일 광량 판정을 근거로 쓰는데 집계가 새벽 2시에 끝나므로
 * 그 전에 돌면 빛 이야기가 빠진다.
 */
@Component
@ConditionalOnProperty(prefix = "potner.diary", name = "enabled", havingValue = "true")
public class DiaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DiaryScheduler.class);

    private final PlantRepository plantRepository;
    private final DiaryGenerationService generationService;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public DiaryScheduler(
            PlantRepository plantRepository,
            DiaryGenerationService generationService,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.plantRepository = plantRepository;
        this.generationService = generationService;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    @Scheduled(cron = "${potner.diary.cron}", zone = "${potner.diary.zone}")
    public void writeYesterdayDiaries() {
        writeFor(serviceToday().minusDays(1));
    }

    /**
     * 날짜 하나를 모든 활성 식물에 대해 처리한다.
     *
     * <p>한 식물이 실패해도 멈추지 않는다. 모델 호출은 네트워크와 정원에 달려 있어 일부가
     * 실패하는 것이 정상이고, 그것 때문에 나머지 식물이 일기를 못 받으면 안 된다.
     * 예상 못 한 예외까지 여기서 삼키는 이유도 같다.
     */
    public Map<DiaryGenerationResult, Integer> writeFor(LocalDate diaryDate) {
        List<Plant> plants = plantRepository.findAllByStatus(PlantStatus.ACTIVE);
        Map<DiaryGenerationResult, Integer> counts = new EnumMap<>(DiaryGenerationResult.class);

        for (Plant plant : plants) {
            DiaryGenerationResult result;
            try {
                result = generationService.generate(plant.getId(), diaryDate);
            } catch (RuntimeException exception) {
                log.warn("Diary generation failed: plantId={}, date={}", plant.getId(), diaryDate, exception);
                result = DiaryGenerationResult.SKIPPED_NO_CONTENT;
            }
            counts.merge(result, 1, Integer::sum);
        }

        if (!plants.isEmpty()) {
            log.info("Diary generation finished: date={}, plants={}, results={}",
                    diaryDate, plants.size(), counts);
        }
        return counts;
    }

    /** 날짜 경계는 서비스 타임존 기준이다. UTC 로 계산하면 전일이 하루 밀린다. */
    private LocalDate serviceToday() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .plusSeconds(sensorQueryProperties.zoneOffsetSeconds())
                .toLocalDate();
    }
}
