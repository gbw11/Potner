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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BloomServiceTest {

    private static final String USER_ID = "10000000-0000-0000-0000-0000000000aa";
    private static final String PLANT_ID = "20000000-0000-0000-0000-0000000000bb";

    /** 한국 시간으로는 2026-07-29 00:30 이다. UTC 로 끊으면 하루 전이 된다. */
    private static final Instant NOW = Instant.parse("2026-07-28T15:30:00Z");
    private static final LocalDate SERVICE_TODAY = LocalDate.of(2026, 7, 29);

    @Mock
    private PlantBloomRepository bloomRepository;

    @Mock
    private PlantRepository plantRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private BloomService bloomService;

    @BeforeEach
    void setUp() {
        bloomService = new BloomService(
                bloomRepository,
                plantRepository,
                eventPublisher,
                new SensorQueryProperties("+09:00", 30, 7, 90),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void bloomDateDefaultsToTodayInServiceTimezone() {
        givenOwnedPlant();
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false);

        BloomResponse response = bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null));

        // UTC 로 계산하면 07-28 이 된다. 그러면 같은 날 찍힌 사진·일기와 날짜가 어긋난다.
        assertThat(response.bloomDate()).isEqualTo(SERVICE_TODAY);
        assertThat(response.source()).isEqualTo(BloomSource.USER);
        assertThat(response.plantName()).isEqualTo("로지");
        // 저장 직후 그대로 응답하므로 DB 기본값에 맡기면 null 로 나간다.
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    void pastBloomDateIsKeptButFutureIsRejected() {
        givenOwnedPlant();
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false);

        assertThat(bloomService.record(
                USER_ID,
                PLANT_ID,
                new RecordBloomRequest(SERVICE_TODAY.minusDays(3), null)).bloomDate())
                .isEqualTo(SERVICE_TODAY.minusDays(3));

        // 며칠 지나서 기록하는 것은 정상이지만 오지 않은 날은 기록할 수 없다.
        assertBusinessError(
                () -> bloomService.record(
                        USER_ID,
                        PLANT_ID,
                        new RecordBloomRequest(SERVICE_TODAY.plusDays(1), null)),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void onlyTheFirstRecordOfAPlantIsAFirstBloom() {
        givenOwnedPlant();
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false, true);

        bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null));
        bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null));

        ArgumentCaptor<BloomRecordedEvent> events =
                ArgumentCaptor.forClass(BloomRecordedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues())
                .extracting(BloomRecordedEvent::firstBloom)
                .containsExactly(true, false);
    }

    @Test
    void sameDayCanHoldMoreThanOneBloom() {
        // 하루에 두 송이가 피는 일이 있다. 날짜로 막으면 정상 기록을 잃는다.
        givenOwnedPlant();
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false, true);

        BloomResponse first = bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null));
        BloomResponse second = bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null));

        assertThat(first.bloomDate()).isEqualTo(second.bloomDate());
        assertThat(first.bloomId()).isNotEqualTo(second.bloomId());
    }

    @Test
    void blankNoteIsStoredAsNothing() {
        givenOwnedPlant();
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false);

        // 공백만 남은 메모가 그대로 들어가면 목록에 빈 줄이 생긴다.
        assertThat(bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, "   ")).note())
                .isNull();
    }

    @Test
    void otherUsersPlantCannotBeRecordedOn() {
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.empty());

        assertBusinessError(
                () -> bloomService.record(USER_ID, PLANT_ID, new RecordBloomRequest(null, null)),
                ErrorCode.PLANT_NOT_FOUND);
        verify(bloomRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(BloomRecordedEvent.class));
    }

    @Test
    void readMarkingHidesOtherUsersRecordAsNotFound() {
        when(bloomRepository.findByIdAndUserId("bloom-id", USER_ID)).thenReturn(Optional.empty());

        assertBusinessError(
                () -> bloomService.markRead(USER_ID, "bloom-id"),
                ErrorCode.BLOOM_NOT_FOUND);
    }

    @Test
    void readMarkingKeepsTheFirstConfirmationTime() {
        PlantBloom bloom = bloom(SERVICE_TODAY);
        when(bloomRepository.findByIdAndUserId(bloom.getId(), USER_ID)).thenReturn(Optional.of(bloom));

        bloomService.markRead(USER_ID, bloom.getId());
        LocalDateTime firstReadAt = bloom.getReadAt();
        bloomService.markRead(USER_ID, bloom.getId());

        assertThat(bloom.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    void deleteChecksBothThePlantAndTheRecord() {
        givenOwnedPlant();
        when(bloomRepository.findByIdAndPlantId("bloom-id", PLANT_ID)).thenReturn(Optional.empty());

        // 남의 식물 경로로 부르면 식물 단계에서, 내 식물이지만 없는 기록이면 기록 단계에서 막힌다.
        assertBusinessError(
                () -> bloomService.delete(USER_ID, PLANT_ID, "bloom-id"),
                ErrorCode.BLOOM_NOT_FOUND);
        verify(bloomRepository, never()).delete(any());
    }

    @Test
    void listFillsPlantNamesInOneQuery() {
        PlantBloom bloom = bloom(SERVICE_TODAY);
        when(bloomRepository.findOwned(anyString(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(bloom), PageRequest.of(0, 20), 1));
        when(bloomRepository.countByUserIdAndReadAtIsNull(USER_ID)).thenReturn(1L);
        Plant plant = plant();
        when(plantRepository.findAllById(List.of(PLANT_ID))).thenReturn(List.of(plant));

        BloomListResponse response = bloomService.getMyBlooms(USER_ID, false, 0, 20);

        assertThat(response.blooms()).hasSize(1);
        assertThat(response.blooms().getFirst().plantName()).isEqualTo("로지");
        assertThat(response.unreadCount()).isEqualTo(1);
    }

    @Test
    void oversizedPageIsCappedAndInvalidPagingIsRejected() {
        when(bloomRepository.findOwned(anyString(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        assertThat(bloomService.getMyBlooms(USER_ID, false, 0, 500).size()).isEqualTo(100);
        assertBusinessError(
                () -> bloomService.getMyBlooms(USER_ID, false, 0, 0),
                ErrorCode.INVALID_REQUEST);
        assertBusinessError(
                () -> bloomService.getMyBlooms(USER_ID, false, -1, 20),
                ErrorCode.INVALID_REQUEST);
    }

    @Test
    void deviceBloomIsRecordedWithTodayAndDeviceSource() {
        givenSaveReturnsArgument();
        when(bloomRepository.existsByPlantIdAndBloomDateGreaterThanEqual(
                PLANT_ID, SERVICE_TODAY.minusDays(7))).thenReturn(false);
        when(bloomRepository.existsByPlantId(PLANT_ID)).thenReturn(false);

        boolean recorded = bloomService.recordFromDevice(USER_ID, PLANT_ID, "로지", 7);

        assertThat(recorded).isTrue();
        ArgumentCaptor<PlantBloom> bloom = ArgumentCaptor.forClass(PlantBloom.class);
        verify(bloomRepository).save(bloom.capture());
        assertThat(bloom.getValue().getSource()).isEqualTo(BloomSource.DEVICE);
        assertThat(bloom.getValue().getBloomDate()).isEqualTo(SERVICE_TODAY);

        ArgumentCaptor<BloomRecordedEvent> event = ArgumentCaptor.forClass(BloomRecordedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().firstBloom()).isTrue();
        assertThat(event.getValue().plantNickname()).isEqualTo("로지");
    }

    @Test
    void deviceBloomIsSkippedDuringTheCooldownWindow() {
        // 판정이 촬영 각도에 따라 개화 ↔ 영양생장 사이를 오가면 그때마다 "새 개화" 가 된다.
        // 오늘 아침 사용자가 남긴 기록도 세야 같은 꽃이 두 건으로 남지 않는다.
        when(bloomRepository.existsByPlantIdAndBloomDateGreaterThanEqual(
                PLANT_ID, SERVICE_TODAY.minusDays(7))).thenReturn(true);

        boolean recorded = bloomService.recordFromDevice(USER_ID, PLANT_ID, "로지", 7);

        assertThat(recorded).isFalse();
        verify(bloomRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    private void givenOwnedPlant() {
        Plant plant = plant();
        when(plantRepository.findByIdAndUserIdAndStatusNot(PLANT_ID, USER_ID, PlantStatus.DELETED))
                .thenReturn(Optional.of(plant));
    }

    private void givenSaveReturnsArgument() {
        when(bloomRepository.save(any(PlantBloom.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Plant plant() {
        Plant plant = mock(Plant.class);
        org.mockito.Mockito.lenient().when(plant.getId()).thenReturn(PLANT_ID);
        org.mockito.Mockito.lenient().when(plant.getNickname()).thenReturn("로지");
        return plant;
    }

    private PlantBloom bloom(LocalDate bloomDate) {
        return PlantBloom.record(
                USER_ID,
                PLANT_ID,
                bloomDate,
                null,
                BloomSource.USER,
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        );
    }

    private void assertBusinessError(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expected));
    }
}
