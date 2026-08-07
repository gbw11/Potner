package com.potner.photo.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlantPhotoRepository extends JpaRepository<PlantPhoto, String> {

    /**
     * 포토 로그와 타임랩스가 쓰는 기간 조회다. 오래된 것부터 주어야 타임랩스가 시간순으로 재생된다.
     *
     * <p>출처로 걸러 장치 사진만 돌려준다. 사용자가 올린 대표 사진은 촬영 간격이 일정하지 않아
     * 타임랩스 프레임이 될 수 없고, 포토 로그도 장치가 기록한 성장 과정만 보여주기로 했다.
     */
    List<PlantPhoto> findAllByPlantIdAndSourceAndPhotoDateBetweenOrderByCapturedAtAsc(
            String plantId,
            PhotoSource source,
            LocalDate fromInclusive,
            LocalDate toInclusive
    );

    /**
     * 같은 날 두 번 찍히면 타임랩스 프레임이 겹치므로 하루 한 장 정책을 판별할 때 쓴다.
     *
     * <p>출처를 함께 보는 것이 중요하다. 보지 않으면 사용자가 대표 사진을 올린 날에 장치가
     * 그날 사진을 올리지 못한다. 제한의 이유가 프레임 간격이므로 장치에만 적용된다.
     */
    boolean existsByPlantIdAndSourceAndPhotoDate(
            String plantId,
            PhotoSource source,
            LocalDate photoDate
    );

    /** 대표 사진으로 지정하려는 사진이 그 식물의 것인지 확인한다. 남의 사진을 걸 수 없어야 한다. */
    Optional<PlantPhoto> findByIdAndPlantId(String photoId, String plantId);

    /**
     * 일기 상세가 그날 장치 사진을 함께 보여줄 때 쓴다. 없는 날은 비어 있다.
     *
     * <p>{@code findBy...} 가 아니라 {@code findFirst...} 인 이유는 하루 여러 장이 허용되기
     * 때문이다({@code potner.photo.allow-multiple-per-day}). 단수 조회로 두면 같은 날 두 장이
     * 쌓인 순간 {@code IncorrectResultSizeDataAccessException} 이 나서 일기 화면이 통째로
     * 죽는다. 여러 장이면 그날 마지막 사진을 보여준다.
     */
    Optional<PlantPhoto> findFirstByPlantIdAndSourceAndPhotoDateOrderByCapturedAtDesc(
            String plantId,
            PhotoSource source,
            LocalDate photoDate
    );

    /** 일기 목록이 날짜별 썸네일을 한 번에 채우는 데 쓴다. */
    List<PlantPhoto> findAllByPlantIdAndSourceAndPhotoDateIn(
            String plantId,
            PhotoSource source,
            Collection<LocalDate> photoDates
    );

    /** 식물 목록 화면이 대표 사진 썸네일을 한 번에 채우는 데 쓴다. */
    List<PlantPhoto> findAllByIdIn(Collection<String> photoIds);
}
