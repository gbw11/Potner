package com.potner.photo.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PlantPhotoRepository extends JpaRepository<PlantPhoto, String> {

    /** 포토 로그와 타임랩스가 쓰는 기간 조회다. 오래된 것부터 주어야 타임랩스가 시간순으로 재생된다. */
    List<PlantPhoto> findAllByPlantIdAndPhotoDateBetweenOrderByCapturedAtAsc(
            String plantId,
            LocalDate fromInclusive,
            LocalDate toInclusive
    );

    /** 같은 날 두 번 찍히면 타임랩스 프레임이 겹치므로 하루 한 장 정책을 판별할 때 쓴다. */
    boolean existsByPlantIdAndPhotoDate(String plantId, LocalDate photoDate);
}
