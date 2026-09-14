package com.potner.bloom.application;

import com.potner.bloom.domain.BloomSource;
import com.potner.bloom.domain.PlantBloom;
import com.potner.bloom.domain.PlantBloomRepository;
import com.potner.bloom.dto.BloomListResponse;
import com.potner.bloom.dto.BloomResponse;
import com.potner.bloom.dto.RecordBloomRequest;
import com.potner.common.error.BusinessException;
import com.potner.common.error.ErrorCode;
import com.potner.plant.domain.Plant;
import com.potner.plant.domain.PlantRepository;
import com.potner.plant.domain.PlantStatus;
import com.potner.sensor.config.SensorQueryProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 개화 기록 작성과 조회다.
 *
 * <p>이상 알림과 나눠 둔 이유는 {@code plant_bloom} 마이그레이션 주석에 적었다. 요약하면
 * {@code alert} 는 식물·지표별 활성 1건을 DB 제약으로 강제해 두 번째 개화를 저장할 수 없다.
 */
@Service
@Transactional(readOnly = true)
public class BloomService {

    /**
     * 목록 페이지 크기 상한이다.
     *
     * <p>알림은 {@code potner.alert.max-page-size} 로 뺐지만 여기는 상수로 둔다. 개화는
     * 사용자가 눌러야 생기는 기록이라 한 사용자에게 수백 건이 쌓이는 상황이 없고, 운영 중에
     * 값을 조절할 이유도 없다.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private final PlantBloomRepository bloomRepository;
    private final PlantRepository plantRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SensorQueryProperties sensorQueryProperties;
    private final Clock clock;

    public BloomService(
            PlantBloomRepository bloomRepository,
            PlantRepository plantRepository,
            ApplicationEventPublisher eventPublisher,
            SensorQueryProperties sensorQueryProperties,
            Clock clock
    ) {
        this.bloomRepository = bloomRepository;
        this.plantRepository = plantRepository;
        this.eventPublisher = eventPublisher;
        this.sensorQueryProperties = sensorQueryProperties;
        this.clock = clock;
    }

    /**
     * 개화를 기록하고 발송 경로에 알린다.
     *
     * <p>같은 날 두 번 눌러 생긴 중복을 막지 않는다. 하루에 두 송이가 피는 일이 실제로 있어서
     * 날짜로 막으면 정상 기록을 잃는다. 잘못 누른 것은 삭제로 정리한다.
     */
    @Transactional
    public BloomResponse record(String userId, String plantId, RecordBloomRequest request) {
        Plant plant = requireOwnedPlant(userId, plantId);
        LocalDate bloomDate = resolveBloomDate(request.bloomDate());

        // 저장하기 전에 세야 한다. 저장한 뒤에 세면 방금 넣은 기록 때문에 첫 꽃이 없어진다.
        boolean firstBloom = !bloomRepository.existsByPlantId(plantId);

        PlantBloom bloom = bloomRepository.save(PlantBloom.record(
                userId,
                plantId,
                bloomDate,
                request.note(),
                BloomSource.USER,
                nowUtc()
        ));
        eventPublisher.publishEvent(new BloomRecordedEvent(
                bloom.getId(),
                userId,
                plantId,
                plant.getNickname(),
                bloom.getBloomDate(),
                firstBloom
        ));

        return BloomResponse.of(bloom, plant.getNickname());
    }

    /**
     * 사진 판정이 감지한 개화를 기록한다. 호출자는 이미 "새로 피었는지"(직전 판정과의 비교)를
     * 확인했고, 여기서는 기록 중복만 거른다.
     *
     * <p>냉각 기간 안의 기록(수동 포함)이 있으면 남기지 않는다. 판정이 촬영 각도에 따라
     * 개화 ↔ 영양생장 사이를 오가면 그때마다 "새 개화" 가 되는데, 실제 재개화가 그 간격으로
     * 일어나지는 않는다. 냉각을 사용자 기록까지 세는 이유는, 오늘 아침 사용자가 남긴 개화를
     * 오후 사진이 또 남기면 같은 꽃이 두 건이 되기 때문이다.
     *
     * <p>소유권을 검사하지 않는다. 사용자 요청이 아니라 서버 내부(사진 분석)가 부르는 경로라
     * 호출자가 이미 식물을 확정해서 넘긴다.
     *
     * @return 기록을 남겼으면 {@code true}, 냉각 기간에 걸려 건너뛰었으면 {@code false}
     */
    @Transactional
    public boolean recordFromDevice(
            String userId,
            String plantId,
            String plantNickname,
            int cooldownDays
    ) {
        LocalDate bloomDate = serviceToday();
        if (bloomRepository.existsByPlantIdAndBloomDateGreaterThanEqual(
                plantId, bloomDate.minusDays(cooldownDays))) {
            return false;
        }

        // 저장하기 전에 세야 한다. 저장한 뒤에 세면 방금 넣은 기록 때문에 첫 꽃이 없어진다.
        boolean firstBloom = !bloomRepository.existsByPlantId(plantId);

        PlantBloom bloom = bloomRepository.save(PlantBloom.record(
                userId,
                plantId,
                bloomDate,
                null,
                BloomSource.DEVICE,
                nowUtc()
        ));
        eventPublisher.publishEvent(new BloomRecordedEvent(
                bloom.getId(),
                userId,
                plantId,
                plantNickname,
                bloom.getBloomDate(),
                firstBloom
        ));
        return true;
    }

    /** 사용자 개화 기록 목록이다. 개화한 날 기준 최신순이다. */
    public BloomListResponse getMyBlooms(String userId, boolean unreadOnly, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);

        Page<PlantBloom> blooms = bloomRepository.findOwned(
                userId,
                unreadOnly,
                PageRequest.of(page, pageSize)
        );
        Map<String, String> plantNames = findPlantNames(blooms.getContent());

        return new BloomListResponse(
                blooms.getContent().stream()
                        .map(bloom -> BloomResponse.of(bloom, plantNames.get(bloom.getPlantId())))
                        .toList(),
                page,
                pageSize,
                blooms.getTotalElements(),
                blooms.getTotalPages(),
                bloomRepository.countByUserIdAndReadAtIsNull(userId)
        );
    }

    /** 이미 읽은 기록에 다시 호출해도 처음 확인 시각을 유지한다. */
    @Transactional
    public void markRead(String userId, String bloomId) {
        PlantBloom bloom = bloomRepository.findByIdAndUserId(bloomId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOOM_NOT_FOUND));
        bloom.markRead(nowUtc());
    }

    /**
     * 잘못 남긴 기록을 지운다.
     *
     * <p>물리 삭제다. 식물처럼 다른 데이터가 참조하지 않고, 사용자가 방금 잘못 누른 것을
     * 되돌리는 용도라 흔적을 남길 이유가 없다.
     */
    @Transactional
    public void delete(String userId, String plantId, String bloomId) {
        requireOwnedPlant(userId, plantId);
        PlantBloom bloom = bloomRepository.findByIdAndPlantId(bloomId, plantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BLOOM_NOT_FOUND));
        bloomRepository.delete(bloom);
    }

    /**
     * 기록할 날짜를 정한다.
     *
     * <p>비어 있으면 서비스 타임존 기준 오늘이다. UTC 로 계산하면 한국 시간 아침 9시 전에 남긴
     * 기록이 전날로 밀려 그날 사진·일기와 어긋난다.
     */
    private LocalDate resolveBloomDate(LocalDate requested) {
        LocalDate today = serviceToday();
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return requested;
    }

    private LocalDate serviceToday() {
        return nowUtc()
                .plusSeconds(sensorQueryProperties.zoneOffsetSeconds())
                .toLocalDate();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /**
     * 목록에 식물 이름을 채운다. PlantBloom 이 식물을 연관으로 들고 있지 않으므로 한 페이지
     * 분량을 한 번에 조회해 N+1 을 만들지 않는다. 알림 목록과 같은 방식이다.
     */
    private Map<String, String> findPlantNames(List<PlantBloom> blooms) {
        List<String> plantIds = blooms.stream()
                .map(PlantBloom::getPlantId)
                .distinct()
                .toList();
        if (plantIds.isEmpty()) {
            return Map.of();
        }
        return plantRepository.findAllById(plantIds).stream()
                .collect(Collectors.toMap(Plant::getId, Plant::getNickname, (first, second) -> first));
    }

    /** 남의 식물은 존재 여부를 숨기려고 403 이 아니라 404 다. */
    private Plant requireOwnedPlant(String userId, String plantId) {
        return plantRepository.findByIdAndUserIdAndStatusNot(plantId, userId, PlantStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLANT_NOT_FOUND));
    }
}
